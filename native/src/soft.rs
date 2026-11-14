//! Soft bodies: XPBD (Macklin et al. 2016) on tetrahedral meshes.
//!
//! Each constraint has a compliance `alpha = compliance / h^2` (h = substep), so the material
//! stiffness does not depend on the time step. One solver iteration per substep is enough for
//! the stiff cases tested here; more iterations only improve convergence.
//!
//! The XPBD solver includes AABB contacts, vertex self-collision, and equal-and-opposite rigid contacts.

use crate::sph::{
    apply_sphere_contact_velocity, project_sphere_aabb, Aabb, BodyImpulse, RigidAabb, SphereContact,
};
use std::collections::HashSet;

pub type V3 = [f64; 3];

#[inline(always)]
fn add(a: V3, b: V3) -> V3 {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}
#[inline(always)]
fn sub(a: V3, b: V3) -> V3 {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}
#[inline(always)]
fn scale(a: V3, s: f64) -> V3 {
    [a[0] * s, a[1] * s, a[2] * s]
}
#[inline(always)]
fn dot(a: V3, b: V3) -> f64 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
#[inline(always)]
fn cross(a: V3, b: V3) -> V3 {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
#[inline(always)]
fn len(a: V3) -> f64 {
    dot(a, a).sqrt()
}

/// Distance constraint between two particles (edge of the tetrahedral mesh).
#[derive(Clone, Copy, Debug)]
pub struct Distance {
    pub a: u16,
    pub b: u16,
    pub rest: f64,
    pub inv_rest: f64,
    pub compliance: f64,
}

/// Volume constraint on one tetrahedron: keeps its signed volume at the rest value.
#[derive(Clone, Copy, Debug)]
pub struct Volume {
    pub p: [u16; 4],
    pub rest: f64,
    pub compliance: f64,
}

#[derive(Clone, Debug)]
pub struct SoftBody {
    pub pos: Vec<V3>,
    pub prev: Vec<V3>,
    pub vel: Vec<V3>,
    pub inv_mass: Vec<f64>,
    pub distances: Vec<Distance>,
    pub volumes: Vec<Volume>,
    /// Active structural edges, used to exclude mesh neighbours from self-collision.
    edges: HashSet<u64>,
    /// Per-distance and per-volume Lagrange multipliers, reset each substep.
    lambda_d: Vec<f64>,
    lambda_v: Vec<f64>,
    pub gravity: V3,
    /// Floor height (y); particles below it are projected back and lose tangential speed.
    pub floor: Option<f64>,
    pub floor_friction: f64,
    /// Velocity damping per second (0 = none).
    pub damping: f64,
    /// Constraint iterations per substep (lambda persists across them).
    pub iterations: usize,
    /// Particles clamped to the floor during the current substep (for inelastic contact).
    contact: Vec<bool>,
    /// If true, floor contact removes the normal velocity (restitution 0). The position-based
    /// correction would otherwise turn penetration depth into an upward velocity spike.
    pub inelastic_floor: bool,
    /// Air wind velocity (m/s), world axes.
    pub wind: V3,
    /// Aerodynamic drag per particle, kg/m: 0.5 * rho_air * Cd * area. Force = drag * |u| * u,
    /// where u = wind - v. Zero disables wind.
    pub drag: f64,
    /// Enables vertex self-collision (spatial-hash broadphase plus inelastic contact response).
    pub self_collision_enabled: bool,
    /// Reused broadphase/contact buffers avoid per-frame allocations in the XPBD hot path.
    self_collision_scratch: SelfCollisionScratch,
    /// Total structural/volume constraints removed by tearing.
    pub torn_constraints: usize,
}

#[derive(Clone, Copy)]
struct ExternalContact {
    contact: SphereContact,
    target_normal_velocity: f64,
}

#[derive(Default)]
pub struct SoftStepResult {
    pub body_impulses: Vec<BodyImpulse>,
    pub torn_constraints: usize,
}

#[derive(Clone, Copy, Debug)]
struct SelfContact {
    i: usize,
    j: usize,
    normal: V3,
    target_relative_normal_velocity: f64,
}

#[derive(Clone, Debug, Default)]
struct SelfCollisionScratch {
    cells: Vec<[i64; 3]>,
    counts: Vec<usize>,
    cell_indices: Vec<usize>,
    start: Vec<usize>,
    fill: Vec<usize>,
    items: Vec<usize>,
    contacts: Vec<SelfContact>,
    last_positions: Vec<V3>,
    last_inv_mass: Vec<f64>,
    last_edge_count: usize,
    last_query_had_no_contacts: bool,
}

#[inline]
fn edge_key(a: usize, b: usize) -> u64 {
    let (a, b) = if a < b { (a, b) } else { (b, a) };
    ((a as u64) << 32) | b as u64
}

impl SoftBody {
    /// Builds a body from particles and tetrahedra. Rest lengths and volumes come from `rest_pos`.
    /// `density` (kg/m^3) with `tet` volumes gives per-particle mass (1/4 of each adjacent tet).
    /// `distance_compliance` and `volume_compliance` are in m/N (inverse stiffness).
    pub fn from_tets(
        rest_pos: &[V3],
        tets: &[[usize; 4]],
        density: f64,
        distance_compliance: f64,
        volume_compliance: f64,
    ) -> Result<Self, String> {
        let n = rest_pos.len();
        if n > u16::MAX as usize + 1 {
            return Err("too many particles for compact XPBD indices".into());
        }
        if tets.is_empty() {
            return Err("no tetrahedra".into());
        }
        if !(density > 0.0 && density.is_finite()) {
            return Err("density must be positive".into());
        }
        let mut mass = vec![0.0f64; n];
        let mut volumes = Vec::with_capacity(tets.len());
        let mut edges = std::collections::BTreeSet::new();
        for t in tets {
            if t.iter().any(|&i| i >= n) {
                return Err("tetrahedron index out of range".into());
            }
            let p = t.map(|i| rest_pos[i]);
            let v = dot(cross(sub(p[1], p[0]), sub(p[2], p[0])), sub(p[3], p[0])) / 6.0;
            if v.abs() < 1e-12 {
                return Err("degenerate tetrahedron".into());
            }
            for &i in t {
                mass[i] += density * v.abs() / 4.0;
            }
            volumes.push(Volume {
                p: t.map(|i| i as u16),
                rest: v,
                compliance: volume_compliance,
            });
            for (x, y) in [(0, 1), (0, 2), (0, 3), (1, 2), (1, 3), (2, 3)] {
                let (a, b) = (t[x].min(t[y]), t[x].max(t[y]));
                edges.insert((a, b));
            }
        }
        let inv_mass = mass
            .iter()
            .map(|&m| if m > 0.0 { 1.0 / m } else { 0.0 })
            .collect();
        let distances = edges
            .into_iter()
            .map(|(a, b)| {
                let rest = len(sub(rest_pos[a], rest_pos[b]));
                Distance {
                    a: a as u16,
                    b: b as u16,
                    rest,
                    inv_rest: 1.0 / rest,
                    compliance: distance_compliance,
                }
            })
            .collect::<Vec<_>>();
        let edge_keys = distances
            .iter()
            .map(|c| edge_key(c.a as usize, c.b as usize))
            .collect();
        let nd = distances.len();
        let nv = volumes.len();
        Ok(Self {
            pos: rest_pos.to_vec(),
            prev: rest_pos.to_vec(),
            vel: vec![[0.0; 3]; n],
            inv_mass,
            distances,
            volumes,
            edges: edge_keys,
            lambda_d: vec![0.0; nd],
            lambda_v: vec![0.0; nv],
            gravity: [0.0, -9.81, 0.0],
            floor: None,
            floor_friction: 0.5,
            damping: 0.0,
            iterations: 1,
            contact: vec![false; n],
            inelastic_floor: true,
            wind: [0.0; 3],
            drag: 0.0,
            self_collision_enabled: true,
            self_collision_scratch: SelfCollisionScratch::default(),
            torn_constraints: 0,
        })
    }

    /// Pins a particle (infinite mass). Its velocity is cleared.
    pub fn pin(&mut self, i: usize) {
        self.inv_mass[i] = 0.0;
        self.vel[i] = [0.0; 3];
    }

    /// Advances the body with no external block or rigid-body colliders.
    pub fn step(&mut self, dt: f64, substeps: usize) {
        let _ = self.step_coupled(dt, substeps, &[], &[], None);
    }

    /// XPBD substeps with static AABBs, dynamic rigid AABBs, and optional tearing strain.
    pub fn step_coupled(
        &mut self,
        dt: f64,
        substeps: usize,
        obstacles: &[Aabb],
        bodies: &[RigidAabb],
        tear_strain: Option<f64>,
    ) -> SoftStepResult {
        let substeps = substeps.max(1);
        let h = dt / substeps as f64;
        let mut result = SoftStepResult::default();
        let mut external_contacts = if obstacles.is_empty() && bodies.is_empty() {
            Vec::new()
        } else {
            vec![None; self.pos.len()]
        };
        for _ in 0..substeps {
            result.torn_constraints += self.substep(
                h,
                obstacles,
                bodies,
                tear_strain,
                &mut external_contacts,
                &mut result.body_impulses,
            );
        }
        result
    }

    #[allow(clippy::too_many_arguments)]
    fn substep(
        &mut self,
        h: f64,
        obstacles: &[Aabb],
        bodies: &[RigidAabb],
        tear_strain: Option<f64>,
        external_contacts: &mut [Option<ExternalContact>],
        body_impulses: &mut Vec<BodyImpulse>,
    ) -> usize {
        // Predict.
        for i in 0..self.pos.len() {
            self.prev[i] = self.pos[i];
            if self.inv_mass[i] > 0.0 {
                // Air drag relative to the wind, per unit mass (inverse mass).
                let rel = sub(self.wind, self.vel[i]);
                let aero = scale(rel, self.drag * len(rel) * self.inv_mass[i]);
                let v = add(add(self.vel[i], scale(self.gravity, h)), scale(aero, h));
                let v = scale(v, (1.0 - self.damping * h).max(0.0));
                self.vel[i] = v;
                self.pos[i] = add(self.pos[i], scale(v, h));
            }
        }
        let torn = tear_strain.map_or(0, |strain| self.tear_overstretched(strain));
        self.contact.iter_mut().for_each(|c| *c = false);
        self.lambda_d.iter_mut().for_each(|l| *l = 0.0);
        self.lambda_v.iter_mut().for_each(|l| *l = 0.0);
        let inv_h2 = 1.0 / (h * h);
        external_contacts.fill(None);
        self.self_collision_scratch.contacts.clear();

        let iterations = self.iterations.max(1);
        for iteration in 0..iterations {
            // Solve distance constraints. Borrow disjoint arrays and cache endpoint positions so
            // the hot loop avoids repeated Vec indexing and bounds checks.
            {
                let positions = &mut self.pos;
                let inv_mass = &self.inv_mass;
                let lambdas = &mut self.lambda_d;
                for (c, lambda) in self.distances.iter().zip(lambdas.iter_mut()) {
                    let a = c.a as usize;
                    let b = c.b as usize;
                    let pa = positions[a];
                    let pb = positions[b];
                    let d = sub(pa, pb);
                    let d2 = dot(d, d);
                    if d2 < 1e-24 {
                        continue;
                    }
                    // The squared-distance constraint is rescaled by 1/rest, so its value remains
                    // in metres and compliance keeps the same XPBD units without a per-iteration sqrt.
                    let violation = 0.5 * (d2 - c.rest * c.rest) * c.inv_rest;
                    let alpha = c.compliance * inv_h2;
                    let residual = violation + alpha * *lambda;
                    // Skip sub-micrometre corrections, including constraints already converged
                    // in an earlier iteration of this substep.
                    if residual.abs() <= 1e-7 {
                        continue;
                    }
                    let n = scale(d, c.inv_rest);
                    let wa = inv_mass[a];
                    let wb = inv_mass[b];
                    let grad2 = dot(n, n);
                    let w = (wa + wb) * grad2;
                    if w <= 0.0 {
                        continue;
                    }
                    let dl = -residual / (w + alpha);
                    *lambda += dl;
                    positions[a] = add(pa, scale(n, wa * dl));
                    positions[b] = add(pb, scale(n, -wb * dl));
                }
            }
            // Solve volume constraints.
            {
                let positions = &mut self.pos;
                let inv_mass = &self.inv_mass;
                let lambdas = &mut self.lambda_v;
                for (c, lambda) in self.volumes.iter().zip(lambdas.iter_mut()) {
                    let [i0, i1, i2, i3] = c.p.map(usize::from);
                    let p = [positions[i0], positions[i1], positions[i2], positions[i3]];
                    let e1 = sub(p[1], p[0]);
                    let e2 = sub(p[2], p[0]);
                    let e3 = sub(p[3], p[0]);
                    let vol = dot(cross(e1, e2), e3) / 6.0;
                    let violation = vol - c.rest;
                    let alpha = c.compliance * inv_h2;
                    let residual = violation + alpha * *lambda;
                    if residual.abs() <= 1e-13 {
                        continue;
                    }
                    let g1 = scale(cross(e2, e3), 1.0 / 6.0);
                    let g2 = scale(cross(e3, e1), 1.0 / 6.0);
                    let g3 = scale(cross(e1, e2), 1.0 / 6.0);
                    let g0 = scale(add(add(g1, g2), g3), -1.0);
                    let w0 = inv_mass[i0];
                    let w1 = inv_mass[i1];
                    let w2 = inv_mass[i2];
                    let w3 = inv_mass[i3];
                    let w =
                        w0 * dot(g0, g0) + w1 * dot(g1, g1) + w2 * dot(g2, g2) + w3 * dot(g3, g3);
                    if w <= 0.0 {
                        continue;
                    }
                    let dl = -residual / (w + alpha);
                    *lambda += dl;
                    positions[i0] = add(p[0], scale(g0, w0 * dl));
                    positions[i1] = add(p[1], scale(g1, w1 * dl));
                    positions[i2] = add(p[2], scale(g2, w2 * dl));
                    positions[i3] = add(p[3], scale(g3, w3 * dl));
                }
            }
            // Projection and contact constraints are interleaved with XPBD, preventing a later
            // structural solve from re-introducing block, body, or self penetration.
            self.project_floor();
            // Self broadphase is rebuilt only after the final XPBD iteration. This keeps its cost
            // bounded while ensuring the exported end-of-substep configuration is non-penetrating.
            if self.self_collision_enabled && iteration + 1 == iterations {
                self.project_self_contacts();
            }
            if !obstacles.is_empty() || !bodies.is_empty() {
                self.project_external_contacts(obstacles, bodies, external_contacts, body_impulses);
            }
        }

        // Reconstruct velocities. Projection displacement is not a bounce: preserve the inelastic
        // contact-normal velocity solved above and only use the corrected positions for deformation.
        for i in 0..self.pos.len() {
            if self.inv_mass[i] > 0.0 {
                self.vel[i] = scale(sub(self.pos[i], self.prev[i]), 1.0 / h);
                if self.contact[i] && self.inelastic_floor {
                    self.vel[i][1] = 0.0;
                }
            }
        }
        for (i, contact) in external_contacts.iter().enumerate() {
            if let Some(c) = contact {
                let target = c.target_normal_velocity;
                let current = dot(self.vel[i], c.contact.normal);
                // Keep the contact response's normal component while retaining tangential XPBD motion.
                self.vel[i] = add(self.vel[i], scale(c.contact.normal, target - current));
            }
        }
        for contact in self.self_collision_scratch.contacts.iter() {
            let i = contact.i;
            let j = contact.j;
            let w = self.inv_mass[i] + self.inv_mass[j];
            if w <= 0.0 {
                continue;
            }
            let target = contact.target_relative_normal_velocity;
            let current = dot(sub(self.vel[i], self.vel[j]), contact.normal);
            let delta = (target - current) / w;
            self.vel[i] = add(self.vel[i], scale(contact.normal, self.inv_mass[i] * delta));
            self.vel[j] = sub(self.vel[j], scale(contact.normal, self.inv_mass[j] * delta));
        }
        torn
    }

    fn project_external_contacts(
        &mut self,
        obstacles: &[Aabb],
        bodies: &[RigidAabb],
        contacts: &mut [Option<ExternalContact>],
        body_impulses: &mut Vec<BodyImpulse>,
    ) {
        const RADIUS: f64 = 0.025;
        for (i, contact_slot) in contacts.iter_mut().enumerate().take(self.pos.len()) {
            if self.inv_mass[i] <= 0.0 {
                continue;
            }
            for obstacle in obstacles {
                if let Some(contact) = project_sphere_aabb(&mut self.pos[i], *obstacle, RADIUS) {
                    let mass = 1.0 / self.inv_mass[i];
                    let _ = apply_sphere_contact_velocity(
                        &mut self.vel[i],
                        contact,
                        [0.0; 3],
                        0.0,
                        mass,
                    );
                    *contact_slot = Some(ExternalContact {
                        contact,
                        target_normal_velocity: dot(self.vel[i], contact.normal),
                    });
                }
            }
            for body in bodies {
                let obstacle = Aabb {
                    min: body.min,
                    max: body.max,
                };
                if let Some(contact) = project_sphere_aabb(&mut self.pos[i], obstacle, RADIUS) {
                    let mass = 1.0 / self.inv_mass[i];
                    if let Some((impulse, point)) = apply_sphere_contact_velocity(
                        &mut self.vel[i],
                        contact,
                        body.velocity,
                        body.mass,
                        mass,
                    ) {
                        body_impulses.push(BodyImpulse {
                            id: body.id,
                            impulse,
                            point,
                        });
                    }
                    *contact_slot = Some(ExternalContact {
                        contact,
                        target_normal_velocity: dot(self.vel[i], contact.normal),
                    });
                }
            }
        }
    }

    fn project_self_contacts(&mut self) {
        const RADIUS: f64 = 0.025;
        const DIAMETER: f64 = 2.0 * RADIUS;
        const MAX_GRID_CELLS: usize = 1_000_000;
        let Self {
            pos,
            vel,
            inv_mass,
            edges,
            self_collision_scratch: scratch,
            ..
        } = self;
        let SelfCollisionScratch {
            cells,
            counts,
            cell_indices,
            start,
            fill,
            items,
            contacts,
            last_positions,
            last_inv_mass,
            last_edge_count,
            last_query_had_no_contacts,
        } = scratch;
        if *last_query_had_no_contacts
            && *last_edge_count == edges.len()
            && last_positions.as_slice() == pos.as_slice()
            && last_inv_mass.as_slice() == inv_mass.as_slice()
        {
            contacts.clear();
            return;
        }
        let mut cell_size = DIAMETER;
        let (mut origin, mut dims, mut cell_count);
        loop {
            cells.clear();
            cells.reserve(pos.len());
            let inv_cell = 1.0 / cell_size;
            for p in pos.iter() {
                cells.push([
                    (p[0] * inv_cell).floor() as i64,
                    (p[1] * inv_cell).floor() as i64,
                    (p[2] * inv_cell).floor() as i64,
                ]);
            }
            origin = [i64::MAX; 3];
            let mut max_cell = [i64::MIN; 3];
            for cell in cells.iter() {
                for k in 0..3 {
                    origin[k] = origin[k].min(cell[k]);
                    max_cell[k] = max_cell[k].max(cell[k]);
                }
            }
            dims = [0usize; 3];
            for k in 0..3 {
                dims[k] = (max_cell[k] - origin[k]) as usize + 1;
            }
            cell_count = dims[0]
                .checked_mul(dims[1])
                .and_then(|n| n.checked_mul(dims[2]))
                .unwrap_or(usize::MAX);
            if cell_count <= MAX_GRID_CELLS {
                break;
            }
            cell_size *= 2.0;
        }

        // Dense counting-sort grid: re-use its storage across substeps/ticks, avoiding per-frame
        // allocations while keeping direct-array lookups in the 3x3x3 self-contact neighborhood.
        counts.resize(cell_count, 0);
        counts.fill(0);
        cell_indices.resize(cells.len(), 0);
        for (i, cell) in cells.iter().enumerate() {
            let local = [
                (cell[0] - origin[0]) as usize,
                (cell[1] - origin[1]) as usize,
                (cell[2] - origin[2]) as usize,
            ];
            let index = (local[2] * dims[1] + local[1]) * dims[0] + local[0];
            cell_indices[i] = index;
            counts[index] += 1;
        }
        start.resize(cell_count + 1, 0);
        start.fill(0);
        for c in 0..cell_count {
            start[c + 1] = start[c] + counts[c];
        }
        fill.resize(cell_count, 0);
        fill.copy_from_slice(&start[..cell_count]);
        items.resize(cells.len(), 0);
        for (i, &cell) in cell_indices.iter().enumerate() {
            items[fill[cell]] = i;
            fill[cell] += 1;
        }

        contacts.clear();
        let inv_sum_radius = 1.0 / cell_size;
        let reach = (DIAMETER * inv_sum_radius).ceil().max(1.0) as i64;
        let diameter2 = DIAMETER * DIAMETER;
        for (i, cell) in cells.iter().copied().enumerate() {
            if inv_mass[i] <= 0.0 {
                continue;
            }
            for dz in -reach..=reach {
                for dy in -reach..=reach {
                    for dx in -reach..=reach {
                        // Visit each cell pair once; within one cell, keep only j > i.
                        if dz < 0 || (dz == 0 && dy < 0) || (dz == 0 && dy == 0 && dx < 0) {
                            continue;
                        }
                        let neighbor = [cell[0] + dx, cell[1] + dy, cell[2] + dz];
                        let mut local = [0usize; 3];
                        let mut inside = true;
                        for k in 0..3 {
                            if neighbor[k] < origin[k] || neighbor[k] - origin[k] >= dims[k] as i64
                            {
                                inside = false;
                                break;
                            }
                            local[k] = (neighbor[k] - origin[k]) as usize;
                        }
                        if !inside {
                            continue;
                        }
                        let cell_index = (local[2] * dims[1] + local[1]) * dims[0] + local[0];
                        for &j in &items[start[cell_index]..start[cell_index + 1]] {
                            if (neighbor == cell && j <= i) || inv_mass[j] <= 0.0 {
                                continue;
                            }
                            let delta = sub(pos[i], pos[j]);
                            let distance2 = dot(delta, delta);
                            if distance2 >= diameter2 || edges.contains(&edge_key(i, j)) {
                                continue;
                            }
                            let distance = distance2.sqrt();
                            let normal = if distance > 1e-12 {
                                scale(delta, 1.0 / distance)
                            } else {
                                let axis = (i.wrapping_mul(31) ^ j.wrapping_mul(17)) % 3;
                                let mut n = [0.0; 3];
                                n[axis] = if (i ^ j) & 1 == 0 { 1.0 } else { -1.0 };
                                n
                            };
                            let inv_i = inv_mass[i];
                            let inv_j = inv_mass[j];
                            let inv_sum = inv_i + inv_j;
                            if inv_sum <= 0.0 {
                                continue;
                            }
                            let correction = (DIAMETER - distance) / inv_sum;
                            pos[i] = add(pos[i], scale(normal, inv_i * correction));
                            pos[j] = sub(pos[j], scale(normal, inv_j * correction));
                            let relative = sub(vel[i], vel[j]);
                            let vn = dot(relative, normal);
                            if vn < 0.0 {
                                let impulse = -vn / inv_sum;
                                vel[i] = add(vel[i], scale(normal, impulse * inv_i));
                                vel[j] = sub(vel[j], scale(normal, impulse * inv_j));
                                let tangent = sub(relative, scale(normal, vn));
                                let tangent_speed = len(tangent);
                                if tangent_speed > 1e-12 {
                                    let friction = (tangent_speed / inv_sum).min(0.25 * impulse);
                                    let f = scale(tangent, friction / tangent_speed);
                                    vel[i] = sub(vel[i], scale(f, inv_i));
                                    vel[j] = add(vel[j], scale(f, inv_j));
                                }
                            }
                            contacts.push(SelfContact {
                                i,
                                j,
                                normal,
                                target_relative_normal_velocity: dot(sub(vel[i], vel[j]), normal),
                            });
                        }
                    }
                }
            }
        }
        *last_query_had_no_contacts = contacts.is_empty();
        if contacts.is_empty() {
            last_positions.clear();
            last_positions.extend_from_slice(pos);
            last_inv_mass.clear();
            last_inv_mass.extend_from_slice(inv_mass);
            *last_edge_count = edges.len();
        }
    }

    /// Removes overloaded structural edges and the volume cells they support. Used by cloth and rope.
    pub fn tear_overstretched(&mut self, strain_limit: f64) -> usize {
        if !(strain_limit.is_finite() && strain_limit > 1.0) {
            return 0;
        }
        let mut broken = HashSet::new();
        for c in &self.distances {
            let a = c.a as usize;
            let b = c.b as usize;
            let stretch = len(sub(self.pos[a], self.pos[b])) / c.rest;
            if stretch >= strain_limit {
                broken.insert(edge_key(a, b));
            }
        }
        if broken.is_empty() {
            return 0;
        }
        let old_distances = self.distances.len();
        self.distances
            .retain(|c| !broken.contains(&edge_key(c.a as usize, c.b as usize)));
        let broken_distances = old_distances - self.distances.len();
        let old_volumes = self.volumes.len();
        self.volumes.retain(|c| {
            let [a, b, c0, d] = c.p.map(usize::from);
            ![(a, b), (a, c0), (a, d), (b, c0), (b, d), (c0, d)]
                .into_iter()
                .any(|(i, j)| broken.contains(&edge_key(i, j)))
        });
        let broken_volumes = old_volumes - self.volumes.len();
        self.lambda_d = vec![0.0; self.distances.len()];
        self.lambda_v = vec![0.0; self.volumes.len()];
        self.edges = self
            .distances
            .iter()
            .map(|c| edge_key(c.a as usize, c.b as usize))
            .collect();
        let count = broken_distances + broken_volumes;
        self.torn_constraints += count;
        count
    }

    /// Non-penetration with the floor plane plus Coulomb friction on this substep's tangential motion.
    /// Static friction cancels tangential motion up to mu * penetration; the rest is kept (kinetic).
    fn project_floor(&mut self) {
        let Some(floor) = self.floor else { return };
        let mu = self.floor_friction.max(0.0);
        for i in 0..self.pos.len() {
            if self.inv_mass[i] > 0.0 && self.pos[i][1] < floor {
                let pen = floor - self.pos[i][1];
                self.pos[i][1] = floor;
                self.contact[i] = true;
                let dx = self.pos[i][0] - self.prev[i][0];
                let dz = self.pos[i][2] - self.prev[i][2];
                let t = (dx * dx + dz * dz).sqrt();
                let max_friction = mu * pen;
                if t <= max_friction {
                    self.pos[i][0] = self.prev[i][0];
                    self.pos[i][2] = self.prev[i][2];
                } else if t > 0.0 {
                    let k = (t - max_friction) / t;
                    self.pos[i][0] = self.prev[i][0] + dx * k;
                    self.pos[i][2] = self.prev[i][2] + dz * k;
                }
            }
        }
    }

    /// Signed volume of every tetrahedron divided by its rest value (1.0 = unchanged).
    pub fn volume_ratios(&self) -> Vec<f64> {
        self.volumes
            .iter()
            .map(|c| {
                let p = c.p.map(|i| self.pos[i as usize]);
                let v = dot(cross(sub(p[1], p[0]), sub(p[2], p[0])), sub(p[3], p[0])) / 6.0;
                v / c.rest
            })
            .collect()
    }

    /// Total kinetic energy (J), for stability checks.
    pub fn kinetic_energy(&self) -> f64 {
        let mut e = 0.0;
        for i in 0..self.pos.len() {
            if self.inv_mass[i] > 0.0 {
                let m = 1.0 / self.inv_mass[i];
                e += 0.5 * m * dot(self.vel[i], self.vel[i]);
            }
        }
        e
    }
}

/// Kuhn subdivision of an `nx` x `ny` x `nz` cell grid into tetrahedra (6 per cell).
/// Returns (rest positions in metres for cell size `h`, tetrahedra). Node index = x + (nx+1)*(y + (ny+1)*z).
pub fn grid_tets(nx: usize, ny: usize, nz: usize, h: f64) -> (Vec<V3>, Vec<[usize; 4]>) {
    let idx = |x: usize, y: usize, z: usize| x + (nx + 1) * (y + (ny + 1) * z);
    let mut pos = Vec::with_capacity((nx + 1) * (ny + 1) * (nz + 1));
    for z in 0..=nz {
        for y in 0..=ny {
            for x in 0..=nx {
                pos.push([x as f64 * h, y as f64 * h, z as f64 * h]);
            }
        }
    }
    // Cube corners in the usual order; main diagonal 0 -> 6.
    let corner = [
        (0, 0, 0),
        (1, 0, 0),
        (1, 1, 0),
        (0, 1, 0),
        (0, 0, 1),
        (1, 0, 1),
        (1, 1, 1),
        (0, 1, 1),
    ];
    let pattern = [
        [0, 1, 2, 6],
        [0, 2, 3, 6],
        [0, 3, 7, 6],
        [0, 7, 4, 6],
        [0, 4, 5, 6],
        [0, 5, 1, 6],
    ];
    let mut tets = Vec::with_capacity(nx * ny * nz * 6);
    for z in 0..nz {
        for y in 0..ny {
            for x in 0..nx {
                let c = corner.map(|(dx, dy, dz)| idx(x + dx, y + dy, z + dz));
                for p in pattern {
                    tets.push(p.map(|k| c[k]));
                }
            }
        }
    }
    (pos, tets)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cube(n: usize, h: f64, density: f64, dc: f64, vc: f64) -> SoftBody {
        let (pos, tets) = grid_tets(n, n, n, h);
        SoftBody::from_tets(&pos, &tets, density, dc, vc).unwrap()
    }

    #[test]
    fn grid_has_consistent_volumes() {
        let (pos, tets) = grid_tets(2, 3, 4, 0.5);
        let total: f64 = tets
            .iter()
            .map(|t| {
                let p = t.map(|i| pos[i]);
                (dot(cross(sub(p[1], p[0]), sub(p[2], p[0])), sub(p[3], p[0])) / 6.0).abs()
            })
            .sum();
        // 2 x 3 x 4 cells of 0.5 m: total volume must equal the box.
        assert!(
            (total - 2.0 * 3.0 * 4.0 * 0.125).abs() < 1e-9,
            "total {total}"
        );
    }

    #[test]
    fn self_collision_separates_nonadjacent_vertices() {
        let mut b = cube(3, 0.05, 1000.0, 1e-5, 1e-7);
        let (a, c) = (0usize, 2usize); // two lattice nodes two cells apart, not a mesh edge
        assert!(!b.edges.contains(&edge_key(a, c)));
        b.pos[a] = [0.0, 0.0, 0.0];
        b.pos[c] = [0.01, 0.0, 0.0];
        b.project_self_contacts();
        let distance = len(sub(b.pos[a], b.pos[c]));
        assert!(
            distance + 1e-9 >= 0.05,
            "self-contact left vertices {distance} m apart"
        );
        assert!(b
            .self_collision_scratch
            .contacts
            .iter()
            .any(|contact| (contact.i, contact.j) == (a, c)));
    }

    #[test]
    fn self_collision_cache_invalidates_for_position_and_mass_changes() {
        let mut moved = cube(3, 0.05, 1000.0, 1e-5, 1e-7);
        moved.project_self_contacts();
        assert!(moved.self_collision_scratch.last_query_had_no_contacts);
        moved.pos[2] = [0.01, 0.0, 0.0];
        moved.project_self_contacts();
        assert!(len(sub(moved.pos[0], moved.pos[2])) + 1e-9 >= 0.05);

        let mut unpinned = cube(3, 0.05, 1000.0, 1e-5, 1e-7);
        unpinned.pos[0] = [0.0, 0.0, 0.0];
        unpinned.pos[2] = [0.01, 0.0, 0.0];
        let inv_mass = unpinned.inv_mass[0];
        unpinned.inv_mass[0] = 0.0;
        unpinned.project_self_contacts();
        assert!(unpinned.self_collision_scratch.last_query_had_no_contacts);
        unpinned.inv_mass[0] = inv_mass;
        unpinned.project_self_contacts();
        assert!(len(sub(unpinned.pos[0], unpinned.pos[2])) + 1e-9 >= 0.05);
    }

    #[test]
    fn sphere_projection_resolves_block_aabb_overlap() {
        let obstacle = Aabb {
            min: [0.0; 3],
            max: [1.0; 3],
        };
        let mut p = [0.01, 0.5, 0.5];
        let contact = project_sphere_aabb(&mut p, obstacle, 0.025).expect("overlapping particle");
        assert!(p[0] <= -0.025 + 1e-8, "particle was not pushed out: {p:?}");
        assert_eq!(contact.normal, [-1.0, 0.0, 0.0]);
    }

    #[test]
    fn overstretched_edges_and_tetrahedra_tear() {
        let mut b = cube(1, 0.05, 1000.0, 1e-5, 1e-7);
        let distance_count = b.distances.len();
        let volume_count = b.volumes.len();
        b.pos[0] = [0.5, 0.5, 0.5];
        let broken = b.tear_overstretched(2.0);
        assert!(broken > 0);
        assert!(b.distances.len() < distance_count);
        assert!(b.volumes.len() < volume_count);
        assert_eq!(b.torn_constraints, broken);
    }

    #[test]
    fn hanging_bar_settles_at_static_sag() {
        // Two particles connected by one distance constraint; the top one pinned.
        let rest = [[0.0, 0.0, 0.0], [0.0, -1.0, 0.0]];
        let mut b = SoftBody::from_tets(
            &[rest[0], rest[1], [0.0, -1.0, 0.1], [0.1, -1.0, 0.0]],
            &[[0, 1, 2, 3]],
            1000.0,
            1e-6,
            1e-6,
        )
        .unwrap();
        b.pin(0);
        b.step(2.0, 40);
        // The tip must stay near its rest length, not fall away.
        let l = len(sub(b.pos[0], b.pos[1]));
        assert!((l - 1.0).abs() < 0.02, "length {l}");
    }

    #[test]
    fn stiffness_is_independent_of_time_step() {
        // Sag of a pinned column under gravity, measured with two steps. Compliance is chosen so the
        // sag is clearly visible (tens of cm for a 1 m column), and the check is relative.
        let sag_for = |dt: f64, subs: usize, iters: usize| {
            let (pos, tets) = grid_tets(2, 4, 2, 0.25);
            let mut b = SoftBody::from_tets(&pos, &tets, 500.0, 2e-4, 1e-7).unwrap();
            b.iterations = iters;
            b.damping = 3.0; // settle to the static state; without damping the reading depends on phase
            for (i, p) in pos.iter().enumerate() {
                if (p[1] - 1.0).abs() < 1e-9 {
                    b.pin(i);
                }
            }
            for _ in 0..(4.0 / dt) as usize {
                b.step(dt, subs);
            }
            let bottom = b.pos.iter().map(|p| p[1]).fold(f64::INFINITY, f64::min);
            0.0 - bottom
        };
        let coarse = sag_for(1.0 / 60.0, 4, 4);
        let fine = sag_for(1.0 / 240.0, 16, 4);
        println!("sag coarse {coarse:.4} m, fine {fine:.4} m");
        assert!(coarse.is_finite() && fine.is_finite());
        assert!(fine > 0.02, "sag must be visible: {fine}");
        assert!(
            (coarse - fine).abs() / fine < 0.05,
            "coarse {coarse} fine {fine}"
        );
    }

    #[test]
    fn jelly_cube_on_floor_keeps_volume_and_stays_finite() {
        let mut b = cube(3, 0.1, 1000.0, 1e-5, 1e-7);
        // Drop from 1 m.
        for p in b.pos.iter_mut() {
            p[1] += 1.0;
        }
        b.prev = b.pos.clone();
        b.floor = Some(0.0);
        for _ in 0..600 {
            b.step(1.0 / 120.0, 4);
        }
        assert!(b.pos.iter().all(|p| p.iter().all(|c| c.is_finite())));
        let worst = b
            .volume_ratios()
            .iter()
            .map(|r| (r - 1.0).abs())
            .fold(0.0, f64::max);
        assert!(worst < 0.10, "volume error {worst}");
        assert!(b.kinetic_energy().is_finite());
    }

    /// Regression (docs/SOFT.md, inversion on impact): a rubber-preset cube dropped on the floor
    /// must never invert a tetrahedron. Before the stiffer preset and inelastic contact the 0.3 m drop
    /// reached a ratio of -0.79 and the 1 m drop -1.10.
    #[test]
    fn rubber_cube_dropped_on_floor_never_inverts() {
        for drop in [0.3, 1.0] {
            let (pos0, tets) = grid_tets(4, 4, 4, 0.05);
            let pos: Vec<V3> = pos0
                .iter()
                .map(|q| [q[0] - 0.1, q[1] + drop, q[2] - 0.1])
                .collect();
            let mut b = SoftBody::from_tets(&pos, &tets, 1100.0, 5e-6, 1e-9).unwrap();
            b.iterations = 4;
            b.damping = 0.2;
            b.floor = Some(0.0);
            let mut worst = f64::MAX;
            for _ in 0..(60 * 8) {
                b.step(0.05, 8);
                worst = worst.min(b.volume_ratios().iter().cloned().fold(f64::MAX, f64::min));
            }
            assert!(
                worst > 0.0,
                "drop {drop} m inverted a tetrahedron: worst ratio {worst}"
            );
            let last = b.volume_ratios().iter().cloned().fold(f64::MAX, f64::min);
            assert!(
                last > 0.9,
                "drop {drop} m did not recover: final ratio {last}"
            );
        }
    }

    #[test]
    fn jelly_settles_and_recovers_shape() {
        // Compressed on the floor, the volume constraints push it back to rest volume.
        let mut b = cube(2, 0.2, 1000.0, 1e-5, 1e-7);
        for p in b.pos.iter_mut() {
            p[1] += 0.3;
            p[0] *= 1.2; // squash horizontally, expanding by 20 %
        }
        b.prev = b.pos.clone();
        b.floor = Some(0.0);
        for _ in 0..1200 {
            b.step(1.0 / 120.0, 4);
        }
        let worst = b
            .volume_ratios()
            .iter()
            .map(|r| (r - 1.0).abs())
            .fold(0.0, f64::max);
        println!(
            "recover: worst volume error {worst:.4}, ke {:.3e}",
            b.kinetic_energy()
        );
        assert!(worst < 0.15, "volume error {worst}");
        assert!(
            b.kinetic_energy() < 1e-2,
            "still moving: {}",
            b.kinetic_energy()
        );
    }

    /// Timing check for one XPBD frame with a single substep (release builds only meaningful).
    /// Run: cargo test --release soft_budget -- --ignored --nocapture
    #[test]
    #[ignore]
    fn soft_budget_1000_particles() {
        for self_collision in [false, true] {
            for iters in [1usize, 4] {
                let mut b = cube(9, 0.1, 1000.0, 1e-5, 1e-6);
                b.iterations = iters;
                b.self_collision_enabled = self_collision;
                b.gravity = [0.0; 3];
                assert_eq!(b.pos.len(), 1000);
                // Warm the reused self-collision buffers and time a stationary rest-shape workload.
                for _ in 0..100 {
                    b.step(1.0 / 120.0, 1);
                }
                let steps = 1000;
                let start = std::time::Instant::now();
                for _ in 0..steps {
                    b.step(1.0 / 120.0, 1);
                }
                let per_step = start.elapsed().as_secs_f64() * 1e3 / steps as f64;
                println!("1000 particles, {iters} XPBD iterations, self-collision {self_collision}: {per_step:.3} ms/frame ({} tets, {} distances)", b.volumes.len(), b.distances.len());
                if iters == 4 && self_collision {
                    assert!(per_step < 1.0, "XPBD budget missed: 1000 particles, 4 iterations, self-collision cost {per_step:.3} ms (limit 1 ms)");
                }
            }
        }
    }

    #[test]
    #[ignore]
    fn soft_active_budget_1000_particles() {
        for distributed in [false, true] {
            for self_collision in [false, true] {
                let mut b = cube(9, 0.1, 1000.0, 1e-5, 1e-6);
                b.iterations = 4;
                b.self_collision_enabled = self_collision;
                b.gravity = [0.0; 3];
                if distributed {
                    for (i, p) in b.pos.iter_mut().enumerate() {
                        let wave = ((i * 17 % 101) as f64 / 100.0 - 0.5) * 0.01;
                        p[0] += wave;
                    }
                } else {
                    b.pos[555][0] += 0.01;
                }
                b.prev.clone_from(&b.pos);
                let tets = b.volumes.len();
                let distances = b.distances.len();
                for _ in 0..25 {
                    b.step(1.0 / 120.0, 1);
                }
                let steps = 500;
                let start = std::time::Instant::now();
                for _ in 0..steps {
                    b.step(1.0 / 120.0, 1);
                }
                let per_step = start.elapsed().as_secs_f64() * 1e3 / steps as f64;
                let pattern = if distributed { "distributed" } else { "local" };
                println!("1000 particles, 4 XPBD iterations, {pattern} deformation, self-collision {self_collision}: {per_step:.3} ms/frame ({tets} tets, {distances} distances)");
                if !distributed && self_collision {
                    assert!(
                        per_step < 1.0,
                        "XPBD active-local budget missed: {per_step:.3} ms/frame (limit 1 ms)"
                    );
                }
            }
        }
    }
}
