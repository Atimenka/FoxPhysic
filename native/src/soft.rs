//! Soft bodies: XPBD (Macklin et al. 2016) on tetrahedral meshes.
//!
//! Each constraint has a compliance `alpha = compliance / h^2` (h = substep), so the material
//! stiffness does not depend on the time step. One solver iteration per substep is enough for
//! the stiff cases tested here; more iterations only improve convergence.
//!
//! This module is self-contained: no rapier, no JNI. Coupling with the rigid engine is a later step.

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
    pub a: usize,
    pub b: usize,
    pub rest: f64,
    pub compliance: f64,
}

/// Volume constraint on one tetrahedron: keeps its signed volume at the rest value.
#[derive(Clone, Copy, Debug)]
pub struct Volume {
    pub p: [usize; 4],
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
            volumes.push(Volume { p: *t, rest: v, compliance: volume_compliance });
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
            .map(|(a, b)| Distance {
                a,
                b,
                rest: len(sub(rest_pos[a], rest_pos[b])),
                compliance: distance_compliance,
            })
            .collect::<Vec<_>>();
        let nd = distances.len();
        let nv = volumes.len();
        Ok(Self {
            pos: rest_pos.to_vec(),
            prev: rest_pos.to_vec(),
            vel: vec![[0.0; 3]; n],
            inv_mass,
            distances,
            volumes,
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
        })
    }

    /// Pins a particle (infinite mass). Its velocity is cleared.
    pub fn pin(&mut self, i: usize) {
        self.inv_mass[i] = 0.0;
        self.vel[i] = [0.0; 3];
    }

    /// Advances the body by `dt` seconds using `substeps` XPBD substeps (one iteration each).
    pub fn step(&mut self, dt: f64, substeps: usize) {
        let substeps = substeps.max(1);
        let h = dt / substeps as f64;
        for _ in 0..substeps {
            self.substep(h);
        }
    }

    fn substep(&mut self, h: f64) {
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
        self.contact.iter_mut().for_each(|c| *c = false);
        self.lambda_d.iter_mut().for_each(|l| *l = 0.0);
        self.lambda_v.iter_mut().for_each(|l| *l = 0.0);
        for _ in 0..self.iterations.max(1) {
        // Solve distance constraints.
        for k in 0..self.distances.len() {
            let c = self.distances[k];
            let d = sub(self.pos[c.a], self.pos[c.b]);
            let l = len(d);
            if l < 1e-12 {
                continue;
            }
            let n = scale(d, 1.0 / l);
            let violation = l - c.rest;
            let w = self.inv_mass[c.a] + self.inv_mass[c.b];
            if w <= 0.0 {
                continue;
            }
            let alpha = c.compliance / (h * h);
            let dl = (-violation - alpha * self.lambda_d[k]) / (w + alpha);
            self.lambda_d[k] += dl;
            self.pos[c.a] = add(self.pos[c.a], scale(n, self.inv_mass[c.a] * dl));
            self.pos[c.b] = add(self.pos[c.b], scale(n, -self.inv_mass[c.b] * dl));
        }
        // Solve volume constraints.
        for k in 0..self.volumes.len() {
            let c = self.volumes[k];
            let [i0, i1, i2, i3] = c.p;
            let p = c.p.map(|i| self.pos[i]);
            let e1 = sub(p[1], p[0]);
            let e2 = sub(p[2], p[0]);
            let e3 = sub(p[3], p[0]);
            let vol = dot(cross(e1, e2), e3) / 6.0;
            let violation = vol - c.rest;
            let g1 = scale(cross(e2, e3), 1.0 / 6.0);
            let g2 = scale(cross(e3, e1), 1.0 / 6.0);
            let g3 = scale(cross(e1, e2), 1.0 / 6.0);
            let g0 = scale(add(add(g1, g2), g3), -1.0);
            let grads = [g0, g1, g2, g3];
            let mut w = 0.0;
            for (j, &i) in c.p.iter().enumerate() {
                w += self.inv_mass[i] * dot(grads[j], grads[j]);
            }
            if w <= 0.0 {
                continue;
            }
            let alpha = c.compliance / (h * h);
            let dl = (-violation - alpha * self.lambda_v[k]) / (w + alpha);
            self.lambda_v[k] += dl;
            for (j, &i) in [i0, i1, i2, i3].iter().enumerate() {
                let m = self.inv_mass[i];
                self.pos[i] = add(self.pos[i], scale(grads[j], m * dl));
            }
        }
            // Contact inside the iteration loop: floor and constraints are solved together, so a
            // node pushed into the floor cannot keep an inverted tetrahedron inverted.
            self.project_floor();
        }
        // Velocities from the position change over the substep.
        for i in 0..self.pos.len() {
            if self.inv_mass[i] > 0.0 {
                self.vel[i] = scale(sub(self.pos[i], self.prev[i]), 1.0 / h);
                if self.contact[i] && self.inelastic_floor {
                    self.vel[i][1] = 0.0;
                }
            }
        }
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
                let p = c.p.map(|i| self.pos[i]);
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
    let corner = [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0), (0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)];
    let pattern = [[0, 1, 2, 6], [0, 2, 3, 6], [0, 3, 7, 6], [0, 7, 4, 6], [0, 4, 5, 6], [0, 5, 1, 6]];
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
        assert!((total - 2.0 * 3.0 * 4.0 * 0.125).abs() < 1e-9, "total {total}");
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
        assert!((coarse - fine).abs() / fine < 0.05, "coarse {coarse} fine {fine}");
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
        let worst = b.volume_ratios().iter().map(|r| (r - 1.0).abs()).fold(0.0, f64::max);
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
            let pos: Vec<V3> = pos0.iter().map(|q| [q[0] - 0.1, q[1] + drop, q[2] - 0.1]).collect();
            let mut b = SoftBody::from_tets(&pos, &tets, 1100.0, 5e-6, 1e-9).unwrap();
            b.iterations = 4;
            b.damping = 0.2;
            b.floor = Some(0.0);
            let mut worst = f64::MAX;
            for _ in 0..(60 * 8) {
                b.step(0.05, 8);
                worst = worst.min(b.volume_ratios().iter().cloned().fold(f64::MAX, f64::min));
            }
            assert!(worst > 0.0, "drop {drop} m inverted a tetrahedron: worst ratio {worst}");
            let last = b.volume_ratios().iter().cloned().fold(f64::MAX, f64::min);
            assert!(last > 0.9, "drop {drop} m did not recover: final ratio {last}");
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
        let worst = b.volume_ratios().iter().map(|r| (r - 1.0).abs()).fold(0.0, f64::max);
        println!("recover: worst volume error {worst:.4}, ke {:.3e}", b.kinetic_energy());
        assert!(worst < 0.15, "volume error {worst}");
        assert!(b.kinetic_energy() < 1e-2, "still moving: {}", b.kinetic_energy());
    }

    /// Timing check for the 1000-particle budget (release builds only meaningful).
    /// Run: cargo test --release soft_budget -- --ignored --nocapture
    #[test]
    #[ignore]
    fn soft_budget_1000_particles() {
        for iters in [1usize, 4] {
            let mut b = cube(9, 0.1, 1000.0, 1e-5, 1e-6);
            b.iterations = iters;
            assert_eq!(b.pos.len(), 1000);
            let steps = 300;
            let start = std::time::Instant::now();
            for _ in 0..steps {
                b.step(1.0 / 120.0, 4);
            }
            let per_step = start.elapsed().as_secs_f64() * 1e3 / steps as f64;
            println!("1000 particles, {iters} iter/substep: {per_step:.3} ms per step ({} tets, {} distances)", b.volumes.len(), b.distances.len());
        }
    }
}
