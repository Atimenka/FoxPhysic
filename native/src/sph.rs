//! 3D weakly compressible SPH (WCSPH) for water: cubic-spline kernel, Tait equation of state,
//! Monaghan artificial viscosity, and fixed boundary particles on the box walls.
//!
//! Boundary particles fill a shell of three spacings outside the box (floor and four sides; the top
//! is open). They take part in the density sums of fluid particles, so the bottom and side layers
//! are not under-dense, and they repel fluid particles through the pressure term with the mirrored
//! pressure p_w = p_i and rest density rho0 (the "dummy particle" approximation).
//!
//! Static AABBs use inelastic particle projection; dynamic rigid AABBs use the same response and
//! return equal-and-opposite contact impulses. The world adapter additionally applies volume-based
//! Archimedes buoyancy and bounds the number of external colliders before they reach this solver.

use serde::Deserialize;
use std::collections::BTreeMap;

pub type V3 = [f64; 3];

/// Axis-aligned collision boundary in pool-local metres. Minecraft VoxelShapes are decomposed
/// into these boxes by the server adapter; bounds are deliberately capped before reaching SPH.
#[derive(Clone, Copy, Debug, Deserialize)]
pub struct Aabb {
    pub min: V3,
    pub max: V3,
}

impl Aabb {
    pub fn is_valid(self) -> bool {
        (0..3).all(|k| {
            self.min[k].is_finite() && self.max[k].is_finite() && self.max[k] > self.min[k]
        })
    }
}

/// A dynamic rigid-body AABB supplied by the game adapter for local fluid coupling.
#[derive(Clone, Copy, Debug, Deserialize)]
pub struct RigidAabb {
    pub id: i64,
    pub min: V3,
    pub max: V3,
    pub velocity: V3,
    pub mass: f64,
}

/// Equal-and-opposite impulse returned to the rigid-world owner. `point` is pool-local.
#[derive(Clone, Copy, Debug)]
pub struct BodyImpulse {
    pub id: i64,
    pub impulse: V3,
    pub point: V3,
}

#[inline(always)]
fn sub(a: V3, b: V3) -> V3 {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}
#[inline(always)]
fn dot(a: V3, b: V3) -> f64 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
#[inline(always)]
fn add(a: V3, b: V3) -> V3 {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}
#[inline(always)]
fn scale(a: V3, s: f64) -> V3 {
    [a[0] * s, a[1] * s, a[2] * s]
}

/// Geometric data needed to apply the velocity response after a position projection.
#[derive(Clone, Copy, Debug)]
pub(crate) struct SphereContact {
    pub normal: V3,
    pub point: V3,
}

/// Projects a spherical particle out of one AABB. Returns the contact normal and point when overlap
/// occurred; the contact can be retained by an XPBD solver until it reconstructs its velocity.
#[inline]
pub(crate) fn project_sphere_aabb(
    position: &mut V3,
    obstacle: Aabb,
    radius: f64,
) -> Option<SphereContact> {
    let min = obstacle.min;
    let max = obstacle.max;
    let before = *position;
    let inside = (0..3).all(|k| before[k] >= min[k] && before[k] <= max[k]);
    let (normal, penetration, point) = if inside {
        let mut axis = 0;
        let mut sign = -1.0;
        let mut distance = f64::INFINITY;
        for k in 0..3 {
            let toward_min = before[k] - min[k];
            let toward_max = max[k] - before[k];
            if toward_min < distance {
                axis = k;
                sign = -1.0;
                distance = toward_min;
            }
            if toward_max < distance {
                axis = k;
                sign = 1.0;
                distance = toward_max;
            }
        }
        let mut normal = [0.0; 3];
        normal[axis] = sign;
        let mut point = before;
        point[axis] = if sign < 0.0 { min[axis] } else { max[axis] };
        (normal, distance + radius, point)
    } else {
        let point = [0, 1, 2].map(|k| before[k].clamp(min[k], max[k]));
        let delta = sub(before, point);
        let distance = dot(delta, delta).sqrt();
        if distance >= radius || distance <= 1e-12 {
            return None;
        }
        (scale(delta, 1.0 / distance), radius - distance, point)
    };
    *position = add(before, scale(normal, penetration + 1e-9));
    Some(SphereContact { normal, point })
}

/// Applies an inelastic contact impulse. Returns the equal-and-opposite impulse on a dynamic AABB.
#[inline]
pub(crate) fn apply_sphere_contact_velocity(
    velocity: &mut V3,
    contact: SphereContact,
    obstacle_velocity: V3,
    obstacle_mass: f64,
    particle_mass: f64,
) -> Option<(V3, V3)> {
    let relative = sub(*velocity, obstacle_velocity);
    let vn = dot(relative, contact.normal);
    if vn >= 0.0 {
        return None;
    }
    let inv_particle = 1.0 / particle_mass;
    let inv_obstacle = if obstacle_mass > 0.0 {
        1.0 / obstacle_mass
    } else {
        0.0
    };
    let inv_sum = inv_particle + inv_obstacle;
    let normal_impulse = -vn / inv_sum;
    let mut impulse_on_particle = scale(contact.normal, normal_impulse);
    let tangent = sub(relative, scale(contact.normal, vn));
    let tangent_speed = dot(tangent, tangent).sqrt();
    if tangent_speed > 1e-12 {
        let friction_impulse = (tangent_speed / inv_sum).min(0.25 * normal_impulse);
        let friction = scale(tangent, -friction_impulse / tangent_speed);
        for k in 0..3 {
            impulse_on_particle[k] += friction[k];
        }
    }
    for k in 0..3 {
        velocity[k] += impulse_on_particle[k] * inv_particle;
    }
    if obstacle_mass > 0.0 {
        Some((scale(impulse_on_particle, -1.0), contact.point))
    } else {
        None
    }
}

/// Pushes a spherical SPH particle out of an AABB and returns the impulse delivered to the
/// obstacle, together with the contact point. A zero-mass obstacle is static.
fn resolve_sphere_aabb(
    position: &mut V3,
    velocity: &mut V3,
    obstacle: Aabb,
    radius: f64,
    obstacle_velocity: V3,
    obstacle_mass: f64,
    particle_mass: f64,
) -> Option<(V3, V3)> {
    let contact = project_sphere_aabb(position, obstacle, radius)?;
    apply_sphere_contact_velocity(
        velocity,
        contact,
        obstacle_velocity,
        obstacle_mass,
        particle_mass,
    )
}

/// Cubic spline kernel (Monaghan) in 3D, support radius 2h.
#[derive(Clone, Copy, Debug)]
pub struct Kernel {
    pub h: f64,
    sigma: f64,
}

impl Kernel {
    pub fn new(h: f64) -> Self {
        Self {
            h,
            sigma: 1.0 / (std::f64::consts::PI * h * h * h),
        }
    }
    /// W(r). Zero outside 2h.
    pub fn w(&self, r: f64) -> f64 {
        let q = r / self.h;
        if q < 1.0 {
            self.sigma * (1.0 - 1.5 * q * q + 0.75 * q * q * q)
        } else if q < 2.0 {
            self.sigma * 0.25 * (2.0 - q).powi(3)
        } else {
            0.0
        }
    }
    /// dW/dr (scalar derivative with respect to distance).
    pub fn dw(&self, r: f64) -> f64 {
        let q = r / self.h;
        if q < 1.0 {
            self.sigma / self.h * (-3.0 * q + 2.25 * q * q)
        } else if q < 2.0 {
            self.sigma / self.h * (-0.75 * (2.0 - q) * (2.0 - q))
        } else {
            0.0
        }
    }
}

/// Uniform grid with counting-sort storage over a set of points. Its buffers are reused every substep.
#[derive(Clone, Debug)]
struct Grid {
    min: V3,
    dims: [usize; 3],
    cell: f64,
    inv: f64,
    start: Vec<u32>,
    items: Vec<u32>,
    cells: Vec<usize>,
    fill: Vec<u32>,
}

impl Default for Grid {
    fn default() -> Self {
        Self {
            min: [0.0; 3],
            dims: [1; 3],
            cell: 1.0,
            inv: 1.0,
            start: Vec::new(),
            items: Vec::new(),
            cells: Vec::new(),
            fill: Vec::new(),
        }
    }
}

impl Grid {
    fn rebuild(&mut self, points: &[V3], cell: f64) {
        let mut max = [f64::NEG_INFINITY; 3];
        self.min = [f64::INFINITY; 3];
        for p in points {
            for k in 0..3 {
                self.min[k] = self.min[k].min(p[k]);
                max[k] = max[k].max(p[k]);
            }
        }
        if points.is_empty() {
            self.min = [0.0; 3];
            max = [0.0; 3];
        }
        self.cell = cell;
        self.inv = 1.0 / cell;
        for ((dim, &max_axis), &min_axis) in
            self.dims.iter_mut().zip(max.iter()).zip(self.min.iter())
        {
            *dim = ((max_axis - min_axis) * self.inv).floor() as usize + 1;
        }
        let ncell = self.dims[0] * self.dims[1] * self.dims[2];
        self.start.resize(ncell + 1, 0);
        self.start.fill(0);
        self.items.resize(points.len(), 0);
        self.cells.resize(points.len(), 0);
        for (i, &p) in points.iter().enumerate() {
            let c = self.cell_index(p);
            self.cells[i] = c;
            self.start[c + 1] += 1;
        }
        for c in 0..ncell {
            self.start[c + 1] += self.start[c];
        }
        self.fill.resize(ncell, 0);
        self.fill.copy_from_slice(&self.start[..ncell]);
        for (i, &c) in self.cells.iter().enumerate() {
            self.items[self.fill[c] as usize] = i as u32;
            self.fill[c] += 1;
        }
    }

    #[inline]
    fn coord(&self, p: V3) -> [usize; 3] {
        let mut c = [0usize; 3];
        for k in 0..3 {
            let v = ((p[k] - self.min[k]) * self.inv).floor();
            c[k] = if v < 0.0 {
                0
            } else {
                (v as usize).min(self.dims[k] - 1)
            };
        }
        c
    }

    #[inline]
    fn cell_index(&self, p: V3) -> usize {
        let c = self.coord(p);
        (c[2] * self.dims[1] + c[1]) * self.dims[0] + c[0]
    }

    /// Calls `f(j, r_vec, r)` for every point j != self_index within `support` of `pi`, where
    /// `r_vec = pi - all[j]`. The cells within `reach` of the query cell cover the support sphere.
    #[inline]
    fn for_each_neighbour(
        &self,
        all: &[V3],
        pi: V3,
        self_index: usize,
        fluid_count: usize,
        support: f64,
        mut f: impl FnMut(usize, V3, f64),
    ) {
        let c = self.coord(pi);
        let s2 = support * support;
        let reach = (support / self.cell - 1e-9).ceil().max(1.0) as usize;
        let lo = |v: usize| v.saturating_sub(reach);
        let hi = |v: usize, d: usize| (v + reach).min(d - 1);
        for cz in lo(c[2])..=hi(c[2], self.dims[2]) {
            for cy in lo(c[1])..=hi(c[1], self.dims[1]) {
                for cx in lo(c[0])..=hi(c[0], self.dims[0]) {
                    let cell = (cz * self.dims[1] + cy) * self.dims[0] + cx;
                    let (a, b) = (self.start[cell] as usize, self.start[cell + 1] as usize);
                    for &j in &self.items[a..b] {
                        let j = j as usize;
                        // Each fluid-fluid pair is evaluated once. Boundary particles have indices
                        // >= fluid_count and are always considered for every fluid query.
                        if j == self_index || (j < fluid_count && j < self_index) {
                            continue;
                        }
                        let d = sub(pi, all[j]);
                        let r2 = dot(d, d);
                        if r2 < s2 {
                            f(j, d, r2.sqrt());
                        }
                    }
                }
            }
        }
    }
}

/// One neighbour pair, found once per substep. `d` = x_i - x_j; `w` and `dw` are the kernel and its derivative at `r`.
#[derive(Clone, Copy, Debug)]
struct Pair {
    i: usize,
    j: usize,
    r: f64,
    d: V3,
    w: f64,
    dw: f64,
    /// Reciprocal mean density for fluid-fluid viscosity and XSPH passes.
    inv_rho_bar: f64,
}

/// Reused storage for neighbour search and the four SPH evaluation arrays.
#[derive(Clone, Debug, Default)]
struct SphScratch {
    all: Vec<V3>,
    grid: Grid,
    pairs: Vec<Pair>,
    rho: Vec<f64>,
    press: Vec<f64>,
    pressure_factor: Vec<f64>,
    wall_pressure_factor: Vec<f64>,
    acc: Vec<V3>,
    smooth: Vec<V3>,
}

#[derive(Clone, Debug)]
pub struct Fluid {
    pub pos: Vec<V3>,
    pub vel: Vec<V3>,
    pub rho: Vec<f64>,
    pub press: Vec<f64>,
    /// Fixed boundary particles (not integrated).
    pub walls: Vec<V3>,
    /// Particle mass (kg). Fluid and boundary particles share one mass: rho0 * dx^3.
    pub mass: f64,
    pub rho0: f64,
    /// Speed of sound in the equation of state (m/s).
    pub c: f64,
    pub gamma: f64,
    /// Monaghan artificial viscosity coefficient.
    pub alpha_visc: f64,
    /// XSPH velocity smoothing factor (0 = off). Damps the particle noise of weakly compressible SPH
    /// without changing the mean momentum.
    pub xsph: f64,
    pub gravity: V3,
    pub kernel: Kernel,
    pub box_min: V3,
    pub box_max: V3,
    /// Reused evaluation buffers; keeps the native per-substep work allocation-light.
    scratch: SphScratch,
}

impl Fluid {
    /// Creates a fluid of particles on a regular lattice with spacing `dx` inside `[lo, hi]`,
    /// plus boundary particles on the box walls (box `box_min..box_max`, top open).
    pub fn lattice(lo: V3, hi: V3, dx: f64, rho0: f64, c: f64, box_min: V3, box_max: V3) -> Self {
        let mut pos = Vec::new();
        let mut x = lo[0];
        while x <= hi[0] + 1e-9 {
            let mut y = lo[1];
            while y <= hi[1] + 1e-9 {
                let mut z = lo[2];
                while z <= hi[2] + 1e-9 {
                    pos.push([x, y, z]);
                    z += dx;
                }
                y += dx;
            }
            x += dx;
        }
        // Boundary shell: three layers outside the box on the floor and the four sides.
        let mut walls = Vec::new();
        let layers = 3;
        let mut x = box_min[0] - layers as f64 * dx;
        while x <= box_max[0] + layers as f64 * dx + 1e-9 {
            let mut z = box_min[2] - layers as f64 * dx;
            while z <= box_max[2] + layers as f64 * dx + 1e-9 {
                // Floor layers (below the box).
                for k in 1..=layers {
                    walls.push([x, box_min[1] - k as f64 * dx, z]);
                }
                let mut y = box_min[1];
                while y <= box_max[1] + 1e-9 {
                    let outside_x = x < box_min[0] - 1e-9 || x > box_max[0] + 1e-9;
                    let outside_z = z < box_min[2] - 1e-9 || z > box_max[2] + 1e-9;
                    // Side layers: points outside the box in x or z (within the shell thickness).
                    if outside_x || outside_z {
                        walls.push([x, y, z]);
                    }
                    y += dx;
                }
                z += dx;
            }
            x += dx;
        }
        let n = pos.len();
        // Support radius 2.6 dx: about 30 neighbours in 3D, the usual choice for cubic spline SPH.
        let h = 1.3 * dx;
        Self {
            pos,
            vel: vec![[0.0; 3]; n],
            rho: vec![rho0; n],
            press: vec![0.0; n],
            walls,
            mass: rho0 * dx * dx * dx,
            rho0,
            c,
            gamma: 7.0,
            alpha_visc: 0.2,
            xsph: 0.5,
            gravity: [0.0, -9.81, 0.0],
            kernel: Kernel::new(h),
            box_min,
            box_max,
            scratch: SphScratch::default(),
        }
    }

    /// Combined position list used by the brute-force validation fixture.
    #[cfg(test)]
    fn all_positions(&self) -> Vec<V3> {
        let mut all = Vec::with_capacity(self.pos.len() + self.walls.len());
        all.extend_from_slice(&self.pos);
        all.extend_from_slice(&self.walls);
        all
    }

    /// Refreshes reusable density, pressure, acceleration and XSPH buffers for the current positions.
    fn evaluate(&mut self) {
        let n = self.pos.len();
        let support = 2.0 * self.kernel.h;
        let kernel = self.kernel;
        let mass = self.mass;
        let rho0 = self.rho0;
        let c = self.c;
        let gamma = self.gamma;
        let alpha_visc = self.alpha_visc;
        let xsph = self.xsph;
        let gravity = self.gravity;
        let Self {
            pos,
            vel,
            walls,
            scratch,
            ..
        } = self;

        scratch.all.clear();
        scratch.all.reserve(pos.len() + walls.len());
        scratch.all.extend_from_slice(pos);
        scratch.all.extend_from_slice(walls);
        scratch.grid.rebuild(&scratch.all, 0.5 * support);
        {
            let all = &scratch.all;
            let grid = &scratch.grid;
            let pairs = &mut scratch.pairs;
            pairs.clear();
            pairs.reserve(n * 48);
            for i in 0..n {
                grid.for_each_neighbour(all, all[i], i, n, support, |j, d, r| {
                    pairs.push(Pair {
                        i,
                        j,
                        r,
                        d,
                        w: kernel.w(r),
                        dw: kernel.dw(r),
                        inv_rho_bar: 0.0,
                    });
                });
            }
        }

        // Density: self term plus fluid and boundary neighbours.
        let self_density = mass * kernel.w(0.0);
        scratch.rho.resize(n, self_density);
        scratch.rho.fill(self_density);
        for pair in &scratch.pairs {
            scratch.rho[pair.i] += mass * pair.w;
            if pair.j < n {
                scratch.rho[pair.j] += mass * pair.w;
            }
        }
        // Tait equation of state. B keeps the sound speed at rho0 equal to c. Pressure is clamped at zero.
        let b = rho0 * c * c / gamma;
        scratch.press.resize(n, 0.0);
        for (pressure, &density) in scratch.press.iter_mut().zip(&scratch.rho) {
            *pressure = (b * ((density / rho0).powf(gamma) - 1.0)).max(0.0);
        }
        let rho0_inv2 = 1.0 / (rho0 * rho0);
        scratch.pressure_factor.resize(n, 0.0);
        scratch.wall_pressure_factor.resize(n, 0.0);
        for i in 0..n {
            let factor = scratch.press[i] / (scratch.rho[i] * scratch.rho[i]);
            scratch.pressure_factor[i] = factor;
            scratch.wall_pressure_factor[i] = factor + scratch.press[i] * rho0_inv2;
        }
        for pair in &mut scratch.pairs {
            if pair.j < n {
                pair.inv_rho_bar = 1.0 / (0.5 * (scratch.rho[pair.i] + scratch.rho[pair.j]));
            }
        }

        scratch.acc.resize(n, gravity);
        scratch.acc.fill(gravity);
        {
            let pairs = &scratch.pairs;
            let pressure_factor = &scratch.pressure_factor;
            let wall_pressure_factor = &scratch.wall_pressure_factor;
            let acc = &mut scratch.acc;
            for pair in pairs {
                if pair.r <= 1e-12 {
                    continue;
                }
                let (i, j) = (pair.i, pair.j);
                let nh = [pair.d[0] / pair.r, pair.d[1] / pair.r, pair.d[2] / pair.r];
                if j < n {
                    // Symmetric pressure term (conserves momentum between fluid particles).
                    let coef = -mass * (pressure_factor[i] + pressure_factor[j]) * pair.dw;
                    // Monaghan artificial viscosity (only for approaching fluid pairs).
                    let mut pi_visc = 0.0;
                    let vr = dot(sub(vel[i], vel[j]), pair.d);
                    if vr < 0.0 {
                        let h = kernel.h;
                        let mu = h * vr / (pair.r * pair.r + 0.01 * h * h);
                        pi_visc = -alpha_visc * c * mu * pair.inv_rho_bar;
                    }
                    let f = coef - mass * pi_visc * pair.dw;
                    for (k, nk) in nh.iter().enumerate() {
                        acc[i][k] += f * nk;
                        acc[j][k] -= f * nk;
                    }
                } else {
                    // Boundary particle: rest density, mirrored pressure, no velocity, no viscosity.
                    let coef = -mass * wall_pressure_factor[i] * pair.dw;
                    for (a, nk) in acc[i].iter_mut().zip(nh) {
                        *a += coef * nk;
                    }
                }
            }
        }

        // XSPH: each particle's velocity moves toward its fluid neighbours' (averaged) velocity.
        scratch.smooth.resize(n, [0.0; 3]);
        scratch.smooth.fill([0.0; 3]);
        if xsph > 0.0 {
            let pairs = &scratch.pairs;
            let smooth = &mut scratch.smooth;
            for pair in pairs {
                if pair.j >= n {
                    continue;
                }
                let (i, j) = (pair.i, pair.j);
                let f = mass * pair.inv_rho_bar * pair.w;
                for k in 0..3 {
                    let dv = vel[j][k] - vel[i][k];
                    smooth[i][k] += f * dv;
                    smooth[j][k] -= f * dv;
                }
            }
            for value in smooth.iter_mut().flatten() {
                *value *= xsph;
            }
        }
    }

    /// Advances the fluid by `dt` seconds. The caller chooses `dt` (see `stable_dt`).
    pub fn step(&mut self, dt: f64) {
        let _ = self.step_coupled(dt, &[], &[]);
    }

    /// Advances one SPH substep against nearby static and dynamic AABBs. Dynamic contacts return
    /// equal-and-opposite impulses for the rigid solver; the caller applies those at the returned
    /// contact points. The game adapter is responsible for keeping these AABBs local and nearby.
    pub fn step_coupled(
        &mut self,
        dt: f64,
        obstacles: &[Aabb],
        bodies: &[RigidAabb],
    ) -> Vec<BodyImpulse> {
        self.evaluate();
        let box_min = self.box_min;
        let box_max = self.box_max;
        let mass = self.mass;
        let radius = self.kernel.h / 2.6;
        let n = self.pos.len();
        let Self {
            pos,
            vel,
            rho,
            press,
            scratch,
            ..
        } = self;
        std::mem::swap(rho, &mut scratch.rho);
        std::mem::swap(press, &mut scratch.press);
        for (((v, x), a), sm) in vel
            .iter_mut()
            .zip(pos.iter_mut())
            .zip(scratch.acc.iter())
            .zip(scratch.smooth.iter())
        {
            for k in 0..3 {
                v[k] += a[k] * dt;
                x[k] += (v[k] + sm[k]) * dt;
            }
            // Box clamp (safety net; the boundary particles normally keep fluid inside).
            for k in 0..3 {
                if x[k] < box_min[k] {
                    x[k] = box_min[k];
                    if v[k] < 0.0 {
                        v[k] *= -0.2;
                    }
                } else if x[k] > box_max[k] {
                    x[k] = box_max[k];
                    if v[k] > 0.0 {
                        v[k] *= -0.2;
                    }
                }
            }
        }

        let mut impulses: BTreeMap<i64, (V3, V3, f64)> = BTreeMap::new();
        for i in 0..n {
            for obstacle in obstacles {
                resolve_sphere_aabb(
                    &mut pos[i],
                    &mut vel[i],
                    *obstacle,
                    radius,
                    [0.0; 3],
                    0.0,
                    mass,
                );
            }
            for body in bodies {
                if let Some((impulse, point)) = resolve_sphere_aabb(
                    &mut pos[i],
                    &mut vel[i],
                    Aabb {
                        min: body.min,
                        max: body.max,
                    },
                    radius,
                    body.velocity,
                    body.mass,
                    mass,
                ) {
                    let magnitude = dot(impulse, impulse).sqrt();
                    if magnitude > 0.0 {
                        let entry = impulses.entry(body.id).or_insert(([0.0; 3], [0.0; 3], 0.0));
                        for k in 0..3 {
                            entry.0[k] += impulse[k];
                            entry.1[k] += point[k] * magnitude;
                        }
                        entry.2 += magnitude;
                    }
                }
            }
        }
        impulses
            .into_iter()
            .map(|(id, (impulse, weighted_point, weight))| BodyImpulse {
                id,
                impulse,
                point: if weight > 0.0 {
                    scale(weighted_point, 1.0 / weight)
                } else {
                    [0.0; 3]
                },
            })
            .collect()
    }

    /// Time step limited by the CFL-type condition and the force limit.
    pub fn stable_dt(&self) -> f64 {
        let h = self.kernel.h;
        let vmax = self
            .vel
            .iter()
            .map(|v| dot(*v, *v).sqrt())
            .fold(0.0f64, f64::max);
        // CFL 0.31 reduces substeps for the benchmarked pool sizes; the long-run unit tests cover
        // finite state and mass conservation. The force-based bound remains the second limiter.
        let dt_cfl = 0.31 * h / (self.c + vmax);
        let dt_force = 0.25 * (h / 9.81f64).sqrt();
        dt_cfl.min(dt_force)
    }

    /// Total mass of fluid particles (constant: particles are not created or destroyed).
    pub fn total_mass(&self) -> f64 {
        self.mass * self.pos.len() as f64
    }

    /// Mean density over fluid particles (kg/m^3).
    pub fn mean_density(&self) -> f64 {
        self.rho.iter().sum::<f64>() / self.rho.len() as f64
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aabb_contact_pushes_fluid_particle_out() {
        let mut p = [0.5, 0.5, 0.5];
        let mut v = [0.0; 3];
        let impulse = resolve_sphere_aabb(
            &mut p,
            &mut v,
            Aabb {
                min: [0.0; 3],
                max: [1.0; 3],
            },
            0.025,
            [0.0; 3],
            0.0,
            0.125,
        );
        assert!(impulse.is_none());
        assert!(p[0] < 0.0 || p[0] > 1.0 || p[1] < 0.0 || p[1] > 1.0 || p[2] < 0.0 || p[2] > 1.0);
        assert!(v.iter().all(|x| x.is_finite()));
    }

    #[test]
    fn dynamic_aabb_receives_equal_opposite_contact_impulse() {
        let mut p = [-0.02, 0.5, 0.5];
        let mut v = [1.0, 0.3, 0.0];
        let (impulse, point) = resolve_sphere_aabb(
            &mut p,
            &mut v,
            Aabb {
                min: [0.0; 3],
                max: [1.0; 3],
            },
            0.025,
            [0.0; 3],
            2.0,
            0.5,
        )
        .expect("approaching dynamic obstacle must receive an impulse");
        assert!(impulse[0] > 0.0, "{impulse:?}");
        assert!(point[0].abs() < 1e-12, "contact point {point:?}");
        assert!(p[0] < 0.0, "particle must be outside: {p:?}");
        assert!(v[0] < 1.0, "normal speed must be reduced: {v:?}");
    }

    #[test]
    fn kernel_integrates_to_one() {
        let k = Kernel::new(0.1);
        let n = 20000;
        let rmax = 2.0 * k.h;
        let dr = rmax / n as f64;
        let mut s = 0.0;
        for i in 0..n {
            let r = (i as f64 + 0.5) * dr;
            s += 4.0 * std::f64::consts::PI * r * r * k.w(r) * dr;
        }
        assert!((s - 1.0).abs() < 1e-3, "integral {s}");
    }

    #[test]
    fn kernel_derivative_matches_finite_difference() {
        let k = Kernel::new(0.1);
        for &r in &[0.02, 0.07, 0.12, 0.18] {
            let e = 1e-7;
            let fd = (k.w(r + e) - k.w(r - e)) / (2.0 * e);
            assert!(
                (fd - k.dw(r)).abs() < 1e-3 * (1.0 + fd.abs()),
                "r {r}: fd {fd} dw {}",
                k.dw(r)
            );
        }
    }

    #[test]
    fn boundary_shell_surrounds_the_box() {
        let f = Fluid::lattice(
            [0.1, 0.1, 0.1],
            [0.3, 0.3, 0.3],
            0.04,
            1000.0,
            12.0,
            [0.0; 3],
            [0.6; 3],
        );
        assert!(!f.walls.is_empty());
        // Every boundary particle lies outside the open box (or below the floor).
        for w in &f.walls {
            let inside = (0..3).all(|k| w[k] >= 0.0 && w[k] <= 0.6) && w[1] >= 0.0;
            assert!(!inside, "wall particle inside the box: {w:?}");
        }
    }

    /// A 0.4 m column on the floor shell (y from 0) is run for 4 s. Over the following 1 s the bottom
    /// pressure and the top height are time-averaged, because the splash makes single snapshots noisy.
    /// The averaged bottom pressure must match rho*g*depth within 25 % (WCSPH overshoots a few percent),
    /// and the column must be nearly at rest. Slow: a few minutes in release.
    /// Run: cargo test --release --lib hydrostatic -- --ignored --nocapture
    #[test]
    #[ignore]
    fn hydrostatic_pressure_at_bottom_is_close_to_rho_g_h() {
        let dx = 0.04;
        let mut f = Fluid::lattice(
            [0.1, 0.0, 0.1],
            [0.5, 0.3 - 1e-9, 0.5],
            dx,
            1000.0,
            12.0,
            [0.0; 3],
            [0.6; 3],
        );
        let started = std::time::Instant::now();
        let advance = |f: &mut Fluid, span: f64| {
            let mut t = 0.0;
            let mut steps = 0usize;
            while t < span {
                let dt = f.stable_dt();
                f.step(dt);
                t += dt;
                steps += 1;
            }
            steps
        };
        let steps = advance(&mut f, 4.0);
        let (mut p_sum, mut top_sum, mut ke_sum, mut count) = (0.0, 0.0, 0.0, 0usize);
        let bottom: Vec<usize> = (0..f.pos.len())
            .filter(|&i| f.pos[i][1] < 0.5 * dx)
            .collect();
        assert!(!bottom.is_empty());
        for _ in 0..100 {
            advance(&mut f, 0.01);
            let top = f.pos.iter().map(|p| p[1]).fold(f64::NEG_INFINITY, f64::max);
            p_sum += bottom.iter().map(|&i| f.press[i]).sum::<f64>() / bottom.len() as f64;
            top_sum += top;
            ke_sum += f
                .vel
                .iter()
                .map(|v| 0.5 * f.mass * dot(*v, *v))
                .sum::<f64>();
            count += 1;
        }
        let mean_p = p_sum / count as f64;
        let depth = top_sum / count as f64;
        let ke = ke_sum / count as f64;
        let expect = 1000.0 * 9.81 * depth;
        println!(
            "hydrostatic: {steps} steps in {:.1} s; bottom {mean_p:.0} Pa, expected {expect:.0} Pa (depth {depth:.3} m), mean ke {ke:.3} J",
            started.elapsed().as_secs_f64()
        );
        assert!(ke < 2.0, "not settled: ke {ke}");
        assert!(
            (mean_p - expect).abs() / expect < 0.25,
            "bottom {mean_p} expected {expect}"
        );
    }

    /// The pair-list evaluation must equal a brute-force O(N^2) sum over every particle pair,
    /// for a perturbed lattice with velocities (so the viscosity branch is exercised).
    #[test]
    fn pair_evaluation_matches_brute_force() {
        let mut f = Fluid::lattice(
            [0.1, 0.1, 0.1],
            [0.24, 0.24, 0.24],
            0.04,
            1000.0,
            12.0,
            [0.0; 3],
            [0.4; 3],
        );
        let mut seed = 12345u64;
        let mut uniform = || {
            seed = seed
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            (seed >> 11) as f64 / (1u64 << 53) as f64 - 0.5
        };
        for p in f.pos.iter_mut() {
            for x in p.iter_mut() {
                *x += 0.01 * uniform();
            }
        }
        for v in f.vel.iter_mut() {
            for x in v.iter_mut() {
                *x = 0.2 * uniform();
            }
        }
        f.evaluate();
        let ev_rho = f.scratch.rho.clone();
        let ev_acc = f.scratch.acc.clone();
        let ev_smooth = f.scratch.smooth.clone();

        let n = f.pos.len();
        let all = f.all_positions();
        let s2 = (2.0 * f.kernel.h).powi(2);
        let m = f.mass;
        let mut rho = vec![m * f.kernel.w(0.0); n];
        for i in 0..n {
            for j in 0..all.len() {
                let d = sub(all[i], all[j]);
                let r2 = dot(d, d);
                if j != i && r2 < s2 {
                    rho[i] += m * f.kernel.w(r2.sqrt());
                }
            }
        }
        let b = f.rho0 * f.c * f.c / f.gamma;
        let press: Vec<f64> = rho
            .iter()
            .map(|&r| (b * ((r / f.rho0).powf(f.gamma) - 1.0)).max(0.0))
            .collect();
        let mut acc = vec![f.gravity; n];
        let mut smooth = vec![[0.0f64; 3]; n];
        for i in 0..n {
            for j in 0..all.len() {
                let d = sub(all[i], all[j]);
                let r2 = dot(d, d);
                if j == i || r2 >= s2 || r2.sqrt() <= 1e-12 {
                    continue;
                }
                let r = r2.sqrt();
                let dw = f.kernel.dw(r);
                let nh = [d[0] / r, d[1] / r, d[2] / r];
                let (rho_j, p_j, vj) = if j < n {
                    (rho[j], press[j], f.vel[j])
                } else {
                    (f.rho0, press[i], [0.0; 3])
                };
                let coef = -m * (press[i] / (rho[i] * rho[i]) + p_j / (rho_j * rho_j)) * dw;
                let mut pi_visc = 0.0;
                if j < n {
                    let vr = dot(sub(f.vel[i], vj), d);
                    if vr < 0.0 {
                        let mu = f.kernel.h * vr / (r * r + 0.01 * f.kernel.h * f.kernel.h);
                        pi_visc = -f.alpha_visc * f.c * mu / (0.5 * (rho[i] + rho_j));
                    }
                }
                let coef_v = -m * pi_visc * dw;
                for (a, &n) in acc[i].iter_mut().zip(nh.iter()) {
                    *a += (coef + coef_v) * n;
                }
                if j < n {
                    let fx = m / (0.5 * (rho[i] + rho_j)) * f.kernel.w(r) * f.xsph;
                    for ((s, &v), &u) in smooth[i].iter_mut().zip(vj.iter()).zip(f.vel[i].iter()) {
                        *s += fx * (v - u);
                    }
                }
            }
        }
        let scale = |v: &[V3]| {
            v.iter()
                .flat_map(|x| x.iter())
                .fold(1e-30f64, |a, &x| a.max(x.abs()))
        };
        let worst = |a: &[V3], b: &[V3]| {
            let s = scale(b);
            a.iter()
                .zip(b)
                .flat_map(|(x, y)| (0..3).map(move |k| (x[k] - y[k]).abs()))
                .fold(0.0f64, f64::max)
                / s
        };
        let rho_err = ev_rho
            .iter()
            .zip(&rho)
            .map(|(a, b)| (a - b).abs() / b)
            .fold(0.0f64, f64::max);
        assert!(rho_err < 1e-12, "density differs by {rho_err}");
        let acc_err = worst(&ev_acc, &acc);
        assert!(acc_err < 1e-9, "acceleration differs by {acc_err}");
        let sm_err = worst(&ev_smooth, &smooth);
        assert!(sm_err < 1e-9, "xsph differs by {sm_err}");
    }

    #[test]
    fn dam_break_stays_finite_and_spreads() {
        let dx = 0.05;
        let mut f = Fluid::lattice(
            [0.05, 0.05, 0.05],
            [0.25, 0.3, 0.25],
            dx,
            1000.0,
            20.0,
            [0.0; 3],
            [0.8, 0.8, 0.8],
        );
        let n0 = f.pos.len();
        let x0 = f.pos.iter().map(|p| p[0]).fold(f64::NEG_INFINITY, f64::max);
        let mut t = 0.0;
        while t < 0.25 {
            let dt = f.stable_dt().min(5e-4);
            f.step(dt);
            t += dt;
        }
        assert_eq!(f.pos.len(), n0, "particles must be conserved");
        assert!(f.pos.iter().all(|p| p.iter().all(|c| c.is_finite())));
        assert!(f.vel.iter().all(|v| v.iter().all(|c| c.is_finite())));
        let x1 = f.pos.iter().map(|p| p[0]).fold(f64::NEG_INFINITY, f64::max);
        assert!(x1 > x0 + 0.05, "front must advance: {x0} -> {x1}");
        let md = f.mean_density();
        assert!((md / 1000.0 - 1.0).abs() < 0.2, "mean density {md}");
    }
}
