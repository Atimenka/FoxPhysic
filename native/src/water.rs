//! Bounded 2.5D shallow-water equations on a flat bottom.
//!
//! Scheme: finite volume, MUSCL reconstruction of primitives (h, u, v) with a
//! minmod limiter, Rusanov (local Lax-Friedrichs) interface fluxes, SSP-RK2
//! time stepping, reflective walls, adaptive CFL sub-steps, optional Manning
//! bed friction and optional eddy viscosity.
//! Mass is conserved to round-off (flux form, zero mass flux through walls).
//!
//! This is NOT a 3D incompressible/FLIP solver: no overturning, spray,
//! air entrainment, cavities, or two-way body displacement.
use crate::protocol::{TankSpec, TankState};

/// Conserved variables per cell.
#[derive(Clone, Copy, Debug, Default)]
struct Cell {
    h: f64,
    hu: f64,
    hv: f64,
}
impl Cell {
    fn velocity(self) -> (f64, f64) {
        if self.h > 1e-8 {
            (self.hu / self.h, self.hv / self.h)
        } else {
            (0.0, 0.0)
        }
    }
    fn add(&mut self, f: Cell, k: f64) {
        self.h += k * f.h;
        self.hu += k * f.hu;
        self.hv += k * f.hv;
    }
}

/// Primitive variables used for reconstruction.
#[derive(Clone, Copy, Debug, Default)]
struct Prim {
    h: f64,
    u: f64,
    v: f64,
}
impl Prim {
    fn of(c: Cell) -> Self {
        let (u, v) = c.velocity();
        Prim { h: c.h, u, v }
    }
    fn conserved(self) -> Cell {
        Cell {
            h: self.h,
            hu: self.h * self.u,
            hv: self.h * self.v,
        }
    }
    fn mirrored_x(self) -> Self {
        Prim { u: -self.u, ..self }
    }
    fn mirrored_z(self) -> Self {
        Prim { v: -self.v, ..self }
    }
    fn plus(self, o: Prim, k: f64) -> Prim {
        Prim {
            h: self.h + k * o.h,
            u: self.u + k * o.u,
            v: self.v + k * o.v,
        }
    }
}

#[inline]
fn minmod(a: f64, b: f64) -> f64 {
    if a * b <= 0.0 {
        0.0
    } else if a.abs() < b.abs() {
        a
    } else {
        b
    }
}

/// Limited slope of the centre cell. Ghost cells get a zero slope (first order at walls).
#[inline]
fn slope(l: Prim, c: Prim, r: Prim) -> Prim {
    Prim {
        h: minmod(c.h - l.h, r.h - c.h),
        u: minmod(c.u - l.u, r.u - c.u),
        v: minmod(c.v - l.v, r.v - c.v),
    }
}

/// Reusable buffers so the hot loop performs no heap allocation.
#[derive(Clone, Debug, Default)]
struct Workspace {
    pad: Vec<Prim>,
    slope_x: Vec<Prim>,
    slope_z: Vec<Prim>,
}
#[derive(Clone, Debug, Default)]
struct Scratch {
    ws: Workspace,
    rhs: Vec<Cell>,
    stage: Vec<Cell>,
}

#[derive(Clone)]
pub struct Tank {
    pub spec: TankSpec,
    cells: Vec<Cell>,
    scratch: Scratch,
    initial_volume: f64,
    gravity: f64,
}

impl Tank {
    pub fn new(spec: TankSpec, gravity: f32) -> Result<Self, String> {
        spec.validate()?;
        let n = spec.nx * spec.nz;
        let mut cells = Vec::with_capacity(n);
        for _z in 0..spec.nz {
            for x in 0..spec.nx {
                cells.push(Cell {
                    h: if x < spec.nx / 2 {
                        spec.depth_left as f64
                    } else {
                        spec.depth_right as f64
                    },
                    ..Cell::default()
                });
            }
        }
        let initial_volume = cells.iter().map(|c| c.h).sum::<f64>() * (spec.dx as f64).powi(2);
        let padded = (spec.nx + 2) * (spec.nz + 2);
        let scratch = Scratch {
            ws: Workspace {
                pad: vec![Prim::default(); padded],
                slope_x: vec![Prim::default(); padded],
                slope_z: vec![Prim::default(); padded],
            },
            rhs: vec![Cell::default(); n],
            stage: vec![Cell::default(); n],
        };
        Ok(Self {
            spec,
            cells,
            scratch,
            initial_volume,
            gravity: gravity as f64,
        })
    }

    pub fn volume(&self) -> f64 {
        self.cells.iter().map(|c| c.h).sum::<f64>() * (self.spec.dx as f64).powi(2)
    }
    pub fn volume_error(&self) -> f64 {
        (self.volume() - self.initial_volume) / self.initial_volume.max(1e-12)
    }
    pub fn state(&self) -> TankState {
        TankState {
            spec: self.spec.clone(),
            depths: self.cells.iter().map(|c| c.h as f32).collect(),
            volume: self.volume(),
            relative_volume_error: self.volume_error(),
        }
    }
    /// Free-surface height and horizontal velocity at a world position.
    pub fn sample(&self, x: f32, z: f32) -> Option<(f32, [f32; 3])> {
        let ix = ((x - self.spec.origin[0]) / self.spec.dx).floor() as isize;
        let iz = ((z - self.spec.origin[2]) / self.spec.dx).floor() as isize;
        if ix < 0 || iz < 0 || ix >= self.spec.nx as isize || iz >= self.spec.nz as isize {
            return None;
        }
        let c = self.cells[iz as usize * self.spec.nx + ix as usize];
        if c.h <= 1e-8 {
            return None;
        }
        let (u, v) = c.velocity();
        Some((self.spec.origin[1] + c.h as f32, [u as f32, 0.0, v as f32]))
    }

    pub fn advance(&mut self, dt: f64) -> Result<(), String> {
        if !dt.is_finite() || !(0.0..=0.1).contains(&dt) {
            return Err("invalid water dt".into());
        }
        let dx = self.spec.dx as f64;
        let mut remaining = dt;
        let mut iterations = 0;
        while remaining > 1e-12 {
            iterations += 1;
            if iterations > 512 {
                return Err("water CFL budget exceeded; simulation paused".into());
            }
            let mut speed: f64 = 1e-8;
            for c in &self.cells {
                let (u, v) = c.velocity();
                speed = speed.max(u.abs() + v.abs() + 2.0 * (self.gravity * c.h.max(0.0)).sqrt());
            }
            let advective_dt = 0.4 * dx / speed;
            let diffusive_dt = if self.spec.viscosity > 0.0 {
                0.2 * dx * dx / self.spec.viscosity as f64
            } else {
                f64::INFINITY
            };
            let step = remaining.min(advective_dt).min(diffusive_dt);
            self.substep(step)?;
            remaining -= step;
        }
        Ok(())
    }

    fn substep(&mut self, dt: f64) -> Result<(), String> {
        let spec = &self.spec;
        let g = self.gravity;
        let s = &mut self.scratch;
        // SSP-RK2 (Heun): U1 = U + dt L(U); U^{n+1} = 1/2 (U + U1 + dt L(U1)).
        rhs(spec, g, &self.cells, &mut s.ws, &mut s.rhs);
        for ((st, c), r) in s.stage.iter_mut().zip(&self.cells).zip(&s.rhs) {
            *st = Cell {
                h: c.h + dt * r.h,
                hu: c.hu + dt * r.hu,
                hv: c.hv + dt * r.hv,
            };
        }
        rhs(spec, g, &s.stage, &mut s.ws, &mut s.rhs);
        let decay = (-spec.damping as f64 * dt).exp();
        let n_manning2 = (spec.roughness as f64).powi(2) * g;
        for (i, c) in self.cells.iter_mut().enumerate() {
            let st = s.stage[i];
            let r = s.rhs[i];
            let mut next = Cell {
                h: 0.5 * (c.h + st.h + dt * r.h),
                hu: 0.5 * (c.hu + st.hu + dt * r.hu),
                hv: 0.5 * (c.hv + st.hv + dt * r.hv),
            };
            if !next.h.is_finite() || !next.hu.is_finite() || !next.hv.is_finite() {
                return Err("non-finite water state; simulation paused".into());
            }
            if next.h < -1e-6 {
                return Err("negative water depth; simulation paused".into());
            }
            next.h = next.h.max(0.0); // only round-off; never a volume-removing cap
            if next.h < 1e-8 {
                next.hu = 0.0;
                next.hv = 0.0;
            } else {
                // Manning friction, semi-implicit in velocity: S_f = g n^2 |u| u / h^{4/3}.
                if n_manning2 > 0.0 {
                    let (u, v) = next.velocity();
                    let speed = (u * u + v * v).sqrt();
                    let f = 1.0 + dt * n_manning2 * speed / next.h.powf(4.0 / 3.0);
                    next.hu /= f;
                    next.hv /= f;
                }
                next.hu *= decay;
                next.hv *= decay;
            }
            *c = next;
        }
        Ok(())
    }
}

/// Spatial operator L(U) = -div(F) + nu-diffusion, written into `out`.
/// `ws.pad` holds primitive states with one ghost layer on each side; limited
/// slopes are computed once per cell and reused by both neighbouring faces.
fn rhs(spec: &TankSpec, g: f64, cells: &[Cell], ws: &mut Workspace, out: &mut [Cell]) {
    let (nx, nz) = (spec.nx, spec.nz);
    let w = nx + 2;
    let at = |i: usize, j: usize| j * w + i;
    let pad = &mut ws.pad;
    for j in 0..nz {
        for i in 0..nx {
            pad[at(i + 1, j + 1)] = Prim::of(cells[j * nx + i]);
        }
    }
    // Reflective walls: mirror the normal velocity into the ghost layer.
    for j in 1..=nz {
        pad[at(0, j)] = pad[at(1, j)].mirrored_x();
        pad[at(nx + 1, j)] = pad[at(nx, j)].mirrored_x();
    }
    for i in 1..=nx {
        pad[at(i, 0)] = pad[at(i, 1)].mirrored_z();
        pad[at(i, nz + 1)] = pad[at(i, nz)].mirrored_z();
    }
    // Ghost slopes stay at their zero default: first-order accuracy at walls.
    for j in 1..=nz {
        for i in 1..=nx {
            ws.slope_x[at(i, j)] = slope(pad[at(i - 1, j)], pad[at(i, j)], pad[at(i + 1, j)]);
        }
    }
    for i in 1..=nx {
        for j in 1..=nz {
            ws.slope_z[at(i, j)] = slope(pad[at(i, j - 1)], pad[at(i, j)], pad[at(i, j + 1)]);
        }
    }
    let dx = spec.dx as f64;
    let inv_dx = 1.0 / dx;
    for c in out.iter_mut() {
        *c = Cell::default();
    }
    // Interface states from the cell centre plus or minus half its limited slope.
    let face = |l: Prim, sl: Prim, r: Prim, sr: Prim| -> (Prim, Prim) {
        let mut a = l.plus(sl, 0.5);
        let mut b = r.plus(sr, -0.5);
        a.h = a.h.max(0.0);
        b.h = b.h.max(0.0);
        if a.h <= 1e-12 {
            a.u = 0.0;
            a.v = 0.0;
        }
        if b.h <= 1e-12 {
            b.u = 0.0;
            b.v = 0.0;
        }
        (a, b)
    };
    // x-direction interfaces: interface x lies between interior cells x-1 and x.
    for j in 1..=nz {
        for x in 0..=nx {
            let (il, ir) = (x, x + 1);
            let (mut a, mut b) = face(
                pad[at(il, j)],
                ws.slope_x[at(il, j)],
                pad[at(ir, j)],
                ws.slope_x[at(ir, j)],
            );
            // Wall interfaces use the mirrored interior state, so the mass flux is exactly zero.
            if il == 0 {
                a = b.mirrored_x();
            } else if ir == nx + 1 {
                b = a.mirrored_x();
            }
            let flux = rusanov_prim(a, b, g, true);
            let row = (j - 1) * nx;
            if x > 0 {
                out[row + x - 1].add(flux, -inv_dx);
            }
            if x < nx {
                out[row + x].add(flux, inv_dx);
            }
            if x > 0 && x < nx {
                viscous_rate(spec, out, row + x - 1, row + x, cells);
            }
        }
    }
    // z-direction interfaces.
    for i in 1..=nx {
        for z in 0..=nz {
            let (jl, jr) = (z, z + 1);
            let (mut a, mut b) = face(
                pad[at(i, jl)],
                ws.slope_z[at(i, jl)],
                pad[at(i, jr)],
                ws.slope_z[at(i, jr)],
            );
            if jl == 0 {
                a = b.mirrored_z();
            } else if jr == nz + 1 {
                b = a.mirrored_z();
            }
            let flux = rusanov_prim(a, b, g, false);
            let x = i - 1;
            if z > 0 {
                out[(z - 1) * nx + x].add(flux, -inv_dx);
            }
            if z < nz {
                out[z * nx + x].add(flux, inv_dx);
            }
            if z > 0 && z < nz {
                viscous_rate(spec, out, (z - 1) * nx + x, z * nx + x, cells);
            }
        }
    }
}

/// Eddy-viscosity momentum exchange between two neighbouring cells, as a rate.
/// Contributes nu * min(h_a, h_b) * (u_b - u_a) / dx^2 to the momentum rates.
fn viscous_rate(spec: &TankSpec, out: &mut [Cell], a: usize, b: usize, cells: &[Cell]) {
    if spec.viscosity == 0.0 {
        return;
    }
    let ca = cells[a];
    let cb = cells[b];
    let (ua, va) = ca.velocity();
    let (ub, vb) = cb.velocity();
    let factor = spec.viscosity as f64 * ca.h.min(cb.h) / (spec.dx as f64).powi(2);
    let du = factor * (ub - ua);
    let dv = factor * (vb - va);
    out[a].hu += du;
    out[b].hu -= du;
    out[a].hv += dv;
    out[b].hv -= dv;
}

/// Physical flux computed directly from primitives (no repeated divisions).
#[inline]
fn flux_prim(p: Prim, g: f64, x_axis: bool) -> Cell {
    let normal = if x_axis { p.u } else { p.v };
    let mass = p.h * normal;
    let pressure = 0.5 * g * p.h * p.h;
    if x_axis {
        Cell {
            h: mass,
            hu: mass * p.u + pressure,
            hv: mass * p.v,
        }
    } else {
        Cell {
            h: mass,
            hu: mass * p.u,
            hv: mass * p.v + pressure,
        }
    }
}

/// Rusanov (local Lax-Friedrichs) interface flux between two reconstructed states.
#[inline]
fn rusanov_prim(l: Prim, r: Prim, g: f64, x_axis: bool) -> Cell {
    let (ul, ur) = if x_axis { (l.u, r.u) } else { (l.v, r.v) };
    let a = (ul.abs() + (g * l.h).sqrt()).max(ur.abs() + (g * r.h).sqrt());
    let fl = flux_prim(l, g, x_axis);
    let fr = flux_prim(r, g, x_axis);
    let (cl, cr) = (l.conserved(), r.conserved());
    Cell {
        h: 0.5 * (fl.h + fr.h - a * (cr.h - cl.h)),
        hu: 0.5 * (fl.hu + fr.hu - a * (cr.hu - cl.hu)),
        hv: 0.5 * (fl.hv + fr.hv - a * (cr.hv - cl.hv)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn spec(a: f32, b: f32) -> TankSpec {
        TankSpec {
            origin: [0.0; 3],
            nx: 12,
            nz: 8,
            dx: 1.0,
            depth_left: a,
            depth_right: b,
            viscosity: 0.001,
            damping: 0.05,
            roughness: 0.0,
        }
    }
    fn long_spec(a: f32, b: f32, roughness: f32) -> TankSpec {
        TankSpec {
            origin: [0.0; 3],
            nx: 32,
            nz: 2,
            dx: 0.25,
            depth_left: a,
            depth_right: b,
            viscosity: 0.0,
            damping: 0.0,
            roughness,
        }
    }
    /// Position (m) where the depth on row 0 crosses `level` (linear between cell centres),
    /// scanning from the dry side. Returns the origin if the level is never reached.
    fn front_at_level(t: &Tank, level: f64) -> f64 {
        let nx = t.spec.nx;
        let dx = t.spec.dx as f64;
        let x0 = t.spec.origin[0] as f64;
        let centre = |i: usize| x0 + (i as f64 + 0.5) * dx;
        let Some(last) = (0..nx).rev().find(|&i| t.cells[i].h >= level) else {
            return x0;
        };
        if last + 1 >= nx {
            return centre(last);
        }
        let (h0, h1) = (t.cells[last].h, t.cells[last + 1].h);
        let f = (h0 - level) / (h0 - h1).max(1e-12);
        centre(last) + f * dx
    }
    /// Exact Ritter (1892) wet-dry dam break: depth level `h` at time `t` for a dry bed.
    fn ritter_front(g: f64, h0: f64, x0: f64, t: f64, level: f64) -> f64 {
        let c0 = (g * h0).sqrt();
        x0 + (2.0 * c0 - (g * level).sqrt() * 3.0) * t
    }
    #[test]
    fn still_water_stays_still() {
        let mut t = Tank::new(spec(2.0, 2.0), 9.81).unwrap();
        for _ in 0..120 {
            t.advance(1.0 / 120.0).unwrap();
        }
        assert!(t
            .cells
            .iter()
            .all(|c| (c.h - 2.0).abs() < 1e-12 && c.hu.abs() < 1e-12 && c.hv.abs() < 1e-12));
    }
    #[test]
    fn dam_break_conserves_volume() {
        let mut t = Tank::new(spec(3.0, 0.2), 9.81).unwrap();
        for _ in 0..600 {
            t.advance(1.0 / 120.0).unwrap();
        }
        assert!(t.volume_error().abs() < 1e-10, "{}", t.volume_error());
        assert!(t.cells.iter().all(|c| c.h >= 0.0 && c.h.is_finite()));
        assert!(t.cells[11].h > 0.2);
    }
    #[test]
    fn dry_bed_is_nonnegative_and_conservative() {
        let mut t = Tank::new(spec(3.0, 0.0), 9.81).unwrap();
        for _ in 0..240 {
            t.advance(1.0 / 120.0).unwrap();
        }
        assert!(t.volume_error().abs() < 1e-9);
        assert!(t.cells.iter().all(|c| c.h >= 0.0));
    }
    #[test]
    fn ritter_dry_bed_front_matches_analytic_speed() {
        // Dry-bed dam break. Ritter (1892): depth h(x,t) = (2c0 - (x-x0)/t)^2 / (9g)
        // in the wet region, so the 5 cm contour moves at the analytic speed below.
        // Known limitation: the wet/dry tip is smeared (~0.2 m here, first-order scheme ~0.04 m).
        // Waves, not dry fronts, are where the second-order scheme is accurate (see seiche test).
        let g = 9.81f64;
        let h0 = 1.0f64;
        let mut t = Tank::new(long_spec(h0 as f32, 0.0, 0.0), g as f32).unwrap();
        let x0 = 4.0; // dam at the centre of the 8 m domain
        let steps = 48usize; // 0.4 s
        for _ in 0..steps {
            t.advance(1.0 / 120.0).unwrap();
        }
        let elapsed = steps as f64 / 120.0;
        let level = 0.05;
        let analytic = ritter_front(g, h0, x0, elapsed, level);
        let numeric = front_at_level(&t, level);
        assert!(
            (numeric - analytic).abs() < 0.3,
            "5 cm contour {numeric:.3} m vs Ritter {analytic:.3} m"
        );
        assert!(t.volume_error().abs() < 1e-9, "{}", t.volume_error());
    }
    #[test]
    fn seiche_mode_keeps_period_and_amplitude() {
        // Closed basin, first mode: eta = A cos(pi x / L) cos(w t), period 2L/c (linear theory).
        // Second-order reconstruction keeps the amplitude; first-order damping was ~90% after 9 cycles.
        let nx = 32usize;
        let dx = 0.25f64;
        let l = nx as f64 * dx;
        let (h0, a0, g) = (1.0f64, 0.02f64, 9.81f64);
        let mut t = Tank::new(long_spec(1.0, 1.0, 0.0), g as f32).unwrap();
        let x = |i: usize| (i as f64 + 0.5) * dx;
        let shape = |i: usize| (std::f64::consts::PI * x(i) / l).cos();
        for (k, c) in t.cells.iter_mut().enumerate() {
            c.h = h0 + a0 * shape(k % nx);
        }
        let norm: f64 = (0..nx).map(|i| shape(i).powi(2)).sum();
        let period = 2.0 * l / (g * h0).sqrt();
        let mut series = Vec::new();
        for _ in 0..(10.0 * period * 120.0) as usize {
            let proj: f64 = (0..nx).map(|i| (t.cells[i].h - h0) * shape(i)).sum::<f64>() / norm;
            series.push(proj);
            t.advance(1.0 / 120.0).unwrap();
        }
        let mut crossings = Vec::new();
        for k in 1..series.len() {
            if series[k - 1] < 0.0 && series[k] >= 0.0 {
                crossings.push((k - 1) as f64 - series[k - 1] / (series[k] - series[k - 1]));
            }
        }
        let measured = (crossings.last().unwrap() - crossings[0]) / (crossings.len() - 1) as f64 / 120.0;
        let win = (period * 120.0) as usize;
        let amp = |cycle: usize| {
            series[cycle * win..(cycle + 1) * win]
                .iter()
                .fold(0.0f64, |m, v| m.max(v.abs()))
        };
        assert!((measured - period).abs() / period < 0.005, "period {measured} vs {period}");
        assert!(amp(8) / amp(0) > 0.7, "amplitude retained {:.2}", amp(8) / amp(0));
        assert!(t.volume_error().abs() < 1e-9);
    }
    #[test]
    fn manning_friction_slows_the_front_and_conserves_mass() {
        let run = |n: f32| {
            let mut t = Tank::new(long_spec(1.0, 0.0, n), 9.81).unwrap();
            for _ in 0..48 {
                t.advance(1.0 / 120.0).unwrap();
            }
            (front_at_level(&t, 0.05), t.volume_error())
        };
        let (smooth, e0) = run(0.0);
        let (rough, e1) = run(0.1);
        assert!(rough < smooth, "rough {rough} vs smooth {smooth}");
        assert!(e0.abs() < 1e-9 && e1.abs() < 1e-9);
    }
    #[test]
    fn empty_tank_is_stable() {
        let mut t = Tank::new(spec(0.0, 0.0), 9.81).unwrap();
        t.advance(0.05).unwrap();
        assert_eq!(t.volume(), 0.0);
        assert!(t.sample(0.5, 0.5).is_none());
    }
    #[test]
    fn sample_respects_domain() {
        let t = Tank::new(spec(2.0, 2.0), 9.81).unwrap();
        assert!(t.sample(-0.1, 0.5).is_none());
        assert!(t.sample(12.0, 0.5).is_none());
        assert_eq!(t.sample(1.0, 1.0).unwrap().0, 2.0);
    }
    #[test]
    fn invalid_tank_rejected() {
        assert!(Tank::new(spec(-1.0, 0.0), 9.81).is_err());
    }
}
