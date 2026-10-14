//! Engineering approximations: exposed surface pressure/shear and sampled
//! Archimedes buoyancy. All units are SI. These are not full air/water CFD.
use crate::protocol::{Body, Config, Part, WaterCell, Wing};
use crate::water::Tank;
use rapier3d::{
    na::{Point3, Vector3},
    prelude::RigidBody,
};
use std::collections::BTreeMap;

pub type Vec3 = Vector3<f32>;
pub fn v(a: [f32; 3]) -> Vec3 {
    Vec3::new(a[0], a[1], a[2])
}
pub fn array(a: Vec3) -> [f32; 3] {
    [a.x, a.y, a.z]
}

#[derive(Clone, Copy)]
pub struct FluidSample {
    pub bottom_y: f32,
    pub surface_y: f32,
    pub velocity: Vec3,
}
pub struct FluidField<'a> {
    pub tank: Option<&'a Tank>,
    pub cells: &'a BTreeMap<[i32; 3], WaterCell>,
}
impl FluidField<'_> {
    pub fn sample(&self, p: Point3<f32>) -> Option<FluidSample> {
        if let Some(t) = self.tank {
            if let Some((top, flow)) = t.sample(p.x, p.z) {
                return Some(FluidSample {
                    bottom_y: t.spec.origin[1],
                    surface_y: top,
                    velocity: v(flow),
                });
            }
        }
        let x = p.x.floor() as i32;
        let z = p.z.floor() as i32;
        let y = p.y.floor() as i32;
        let (mut bottom, cell) = if let Some(c) = self.cells.get(&[x, y, z]) {
            (y, c)
        } else if let Some(c) = self.cells.get(&[x, y - 1, z]) {
            (y - 1, c)
        } else {
            return None;
        };
        let mut top = bottom as f32 + cell.height;
        for _ in 0..64 {
            let next = top.floor() as i32;
            if (top - next as f32).abs() > 1e-5 {
                break;
            }
            if let Some(c) = self.cells.get(&[x, next, z]) {
                top = next as f32 + c.height;
            } else {
                break;
            }
        }
        for _ in 0..64 {
            if let Some(c) = self.cells.get(&[x, bottom - 1, z]) {
                if c.height >= 0.9999 {
                    bottom -= 1;
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        Some(FluidSample {
            bottom_y: bottom as f32,
            surface_y: top,
            velocity: v(cell.velocity),
        })
    }
}

pub fn wet_fraction(y: f32, half_height: f32, fluid: FluidSample) -> f32 {
    if half_height < 1e-6 {
        return if y >= fluid.bottom_y && y < fluid.surface_y {
            1.0
        } else {
            0.0
        };
    }
    let low = (y - half_height).max(fluid.bottom_y);
    let high = (y + half_height).min(fluid.surface_y);
    ((high - low) / (2.0 * half_height)).clamp(0.0, 1.0)
}

#[derive(Clone)]
pub struct Surface {
    pub center: Vec3,
    pub normal: Vec3,
    pub tangent_a: Vec3,
    pub tangent_b: Vec3,
    pub area: f32,
}
pub fn exposed_surfaces(parts: &[Part]) -> Vec<Surface> {
    let mut out = Vec::new();
    for (i, p) in parts.iter().enumerate() {
        for axis in 0..3 {
            for sign in [-1.0, 1.0] {
                let mut n = Vec3::zeros();
                n[axis] = sign;
                let center = v(p.offset) + n * (p.size[axis] * 0.5);
                let a = (axis + 1) % 3;
                let b = (axis + 2) % 3;
                let covered = parts.iter().enumerate().any(|(j, q)| {
                    i != j
                        && (center[axis] - (q.offset[axis] - sign * q.size[axis] * 0.5)).abs()
                            < 1e-4
                        && (q.offset[a] - p.offset[a]).abs() + p.size[a] * 0.5
                            <= q.size[a] * 0.5 + 1e-4
                        && (q.offset[b] - p.offset[b]).abs() + p.size[b] * 0.5
                            <= q.size[b] * 0.5 + 1e-4
                });
                if covered {
                    continue;
                }
                let mut ta = Vec3::zeros();
                ta[a] = p.size[a] * 0.5;
                let mut tb = Vec3::zeros();
                tb[b] = p.size[b] * 0.5;
                out.push(Surface {
                    center,
                    normal: n,
                    tangent_a: ta,
                    tangent_b: tb,
                    area: p.size[a] * p.size[b],
                });
            }
        }
    }
    out
}

#[derive(Clone, Copy)]
pub struct PanelModel {
    pub density: f32,
    pub pressure_coefficient: f32,
    pub shear_coefficient: f32,
}
// Pressure acts on the windward side. Tangential friction opposes relative
// motion. The rational drag limiter prevents explicit-step velocity reversal.
pub fn panel_force(
    relative_flow: Vec3,
    normal: Vec3,
    area: f32,
    model: PanelModel,
    mass_share: f32,
    dt: f32,
) -> Vec3 {
    let PanelModel {
        density: rho,
        pressure_coefficient: cd,
        shear_coefficient: cf,
    } = model;
    if rho <= 0.0 || area <= 0.0 {
        return Vec3::zeros();
    }
    let vn = relative_flow.dot(&normal);
    let tangent = relative_flow - normal * vn;
    let raw = -normal * (0.5 * rho * cd * vn.min(0.0).powi(2) * area)
        + tangent * (0.5 * rho * cf * tangent.norm() * area);
    let speed = relative_flow.norm();
    raw / (1.0 + raw.norm() * dt / (mass_share.max(1e-6) * speed.max(1e-5)))
}

// Symmetric thin-airfoil slope, induced drag and an empirical stall blend.
// Valid only for a deliberately declared wing, not arbitrary Minecraft blocks.
pub fn wing_force(
    velocity_in_air: Vec3,
    span: Vec3,
    chord: Vec3,
    normal: Vec3,
    wing: &Wing,
    rho: f32,
) -> Vec3 {
    let speed = velocity_in_air.norm();
    if speed < 0.01 || rho == 0.0 {
        return Vec3::zeros();
    }
    let flow_direction = velocity_in_air / speed;
    let forward = velocity_in_air.dot(&chord).abs();
    let alpha = (-velocity_in_air.dot(&normal)).atan2(forward.max(1e-5));
    let a = alpha.abs();
    let stall = 15.0_f32.to_radians();
    let full_stall = 25.0_f32.to_radians();
    let linear = std::f32::consts::TAU * alpha;
    let plate = 1.1 * (2.0 * alpha).sin();
    let t = ((a - stall) / (full_stall - stall)).clamp(0.0, 1.0);
    let blend = t * t * (3.0 - 2.0 * t);
    let cl = (linear * (1.0 - blend) + plate * blend).clamp(-1.8, 1.8);
    let cd = 0.025
        + cl * cl / (std::f32::consts::PI * wing.aspect_ratio * 0.8)
        + 0.6 * blend * alpha.sin().powi(2);
    let lift = flow_direction.cross(&span);
    let lift_direction = if lift.norm_squared() > 1e-8 {
        lift.normalize()
    } else {
        Vec3::zeros()
    };
    let pressure = 0.5 * rho * speed * speed * wing.area;
    (lift_direction * cl - flow_direction * cd) * pressure
}

pub struct ForceBundle {
    pub forces: Vec<(Vec3, Point3<f32>)>,
    pub buoyancy: Vec3,
    pub air: Vec3,
}
pub fn body_forces(
    rb: &RigidBody,
    spec: &Body,
    surfaces: &[Surface],
    fluid: &FluidField<'_>,
    cfg: &Config,
    wind: Vec3,
    dt: f32,
) -> ForceBundle {
    let mut out = ForceBundle {
        forces: Vec::new(),
        buoyancy: Vec3::zeros(),
        air: Vec3::zeros(),
    };
    let iso = rb.position();
    let rot = iso.rotation;
    // 4^3 quadrature samples per cuboid. For aligned cuboids in flat water the
    // vertical interval fractions recover the submerged volume exactly.
    let n = 4;
    for part in &spec.parts {
        let sub_half = v(part.size) / (2.0 * n as f32);
        let world_half_y = (rot * Vec3::new(sub_half.x, 0.0, 0.0)).y.abs()
            + (rot * Vec3::new(0.0, sub_half.y, 0.0)).y.abs()
            + (rot * Vec3::new(0.0, 0.0, sub_half.z)).y.abs();
        let dv = part.volume() / (n * n * n) as f32;
        for x in 0..n {
            for y in 0..n {
                for z in 0..n {
                    let local = v(part.offset)
                        + Vec3::new(
                            ((x as f32 + 0.5) / n as f32 - 0.5) * part.size[0],
                            ((y as f32 + 0.5) / n as f32 - 0.5) * part.size[1],
                            ((z as f32 + 0.5) / n as f32 - 0.5) * part.size[2],
                        );
                    let p = iso * Point3::from(local);
                    if let Some(w) = fluid.sample(p) {
                        let fraction = wet_fraction(p.y, world_half_y, w);
                        if fraction > 0.0 {
                            let f = Vec3::new(
                                0.0,
                                cfg.water_density * cfg.gravity * dv * fraction,
                                0.0,
                            );
                            out.buoyancy += f;
                            out.forces.push((f, p));
                        }
                    }
                }
            }
        }
    }
    let total_area = surfaces.iter().map(|s| s.area).sum::<f32>().max(1e-5);
    for s in surfaces {
        let normal = rot * s.normal;
        let ta = rot * s.tangent_a;
        let tb = rot * s.tangent_b;
        let half_height = (ta.y.abs() + tb.y.abs()) * 0.5;
        for a in [-0.5, 0.5] {
            for b in [-0.5, 0.5] {
                let p = iso * Point3::from(s.center + s.tangent_a * a + s.tangent_b * b);
                let velocity = rb.velocity_at_point(&p);
                let sample = fluid.sample(p);
                let wet = sample
                    .map(|w| wet_fraction(p.y, half_height, w))
                    .unwrap_or(0.0);
                let area = s.area * 0.25;
                let mass_share = rb.mass() * area / total_area;
                if wet < 1.0 && spec.wing.is_none() {
                    let f = panel_force(
                        wind - velocity,
                        normal,
                        area * (1.0 - wet),
                        PanelModel {
                            density: cfg.air_density,
                            pressure_coefficient: 1.05,
                            shear_coefficient: 0.008,
                        },
                        mass_share,
                        dt,
                    );
                    out.air += f;
                    if f.norm_squared() > 1e-12 {
                        out.forces.push((f, p));
                    }
                }
                if let Some(w) = sample {
                    if wet > 0.0 {
                        let f = panel_force(
                            w.velocity - velocity,
                            normal,
                            area * wet,
                            PanelModel {
                                density: cfg.water_density,
                                pressure_coefficient: 0.9,
                                shear_coefficient: 0.006,
                            },
                            mass_share,
                            dt,
                        );
                        if f.norm_squared() > 1e-12 {
                            out.forces.push((f, p));
                        }
                    }
                }
            }
        }
    }
    if let Some(wing) = &spec.wing {
        let p = iso * Point3::from(v(wing.center));
        let wet = fluid
            .sample(p)
            .map(|w| wet_fraction(p.y, 0.01, w))
            .unwrap_or(0.0);
        let raw = wing_force(
            rb.velocity_at_point(&p) - wind,
            rot * v(wing.span),
            rot * v(wing.chord),
            rot * v(wing.normal),
            wing,
            cfg.air_density,
        ) * (1.0 - wet);
        let force = raw
            / (1.0
                + raw.norm() * dt
                    / (rb.mass().max(1e-5) * (rb.velocity_at_point(&p) - wind).norm().max(1.0)));
        out.air += force;
        out.forces.push((force, p));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    fn part(offset: [f32; 3]) -> Part {
        Part {
            offset,
            size: [1.0; 3],
            density: 550.0,
            friction: 0.6,
            restitution: 0.05,
            block: "minecraft:oak_planks".into(),
        }
    }
    #[test]
    fn interior_faces_removed() {
        assert_eq!(exposed_surfaces(&[part([0.0; 3])]).len(), 6);
        assert_eq!(
            exposed_surfaces(&[part([0.0; 3]), part([1.0, 0.0, 0.0])]).len(),
            10
        );
    }
    #[test]
    fn half_submerged_fraction() {
        let w = FluidSample {
            bottom_y: -10.0,
            surface_y: 0.0,
            velocity: Vec3::zeros(),
        };
        assert!((wet_fraction(0.0, 0.5, w) - 0.5).abs() < 1e-6);
        assert_eq!(wet_fraction(-1.0, 0.5, w), 1.0);
        assert_eq!(wet_fraction(1.0, 0.5, w), 0.0);
    }
    #[test]
    fn bottom_boundary_limits_volume() {
        let w = FluidSample {
            bottom_y: 0.0,
            surface_y: 1.0,
            velocity: Vec3::zeros(),
        };
        assert!((wet_fraction(0.0, 0.5, w) - 0.5).abs() < 1e-6);
    }
    #[test]
    fn no_flow_no_drag() {
        assert_eq!(
            panel_force(
                Vec3::zeros(),
                Vec3::x(),
                1.0,
                PanelModel {
                    density: 1.225,
                    pressure_coefficient: 1.0,
                    shear_coefficient: 0.01
                },
                10.0,
                0.01
            ),
            Vec3::zeros()
        );
    }
    #[test]
    fn drag_opposes_motion() {
        let velocity = Vec3::new(10.0, 2.0, 0.0);
        let f = panel_force(
            -velocity,
            Vec3::x(),
            1.0,
            PanelModel {
                density: 998.0,
                pressure_coefficient: 0.9,
                shear_coefficient: 0.01,
            },
            10.0,
            0.008,
        );
        assert!(f.dot(&velocity) < 0.0);
        assert!(f.norm() * 0.008 < 10.0 * velocity.norm());
    }
    #[test]
    fn positive_angle_of_attack_lifts() {
        let w = Wing {
            center: [0.0; 3],
            span: [1.0, 0.0, 0.0],
            chord: [0.0, 0.0, 1.0],
            normal: [0.0, 1.0, 0.0],
            area: 4.8,
            aspect_ratio: 4.0,
        };
        let f = wing_force(
            Vec3::new(0.0, -2.0, 20.0),
            Vec3::x(),
            Vec3::z(),
            Vec3::y(),
            &w,
            1.225,
        );
        assert!(f.y > 0.0);
        assert!(f.z < 0.0);
        assert_eq!(
            wing_force(Vec3::zeros(), Vec3::x(), Vec3::z(), Vec3::y(), &w, 1.225),
            Vec3::zeros()
        );
    }
}
