//! Pools of 3D SPH water (see `sph.rs`), driven through JSON requests like `soft_world.rs`.
//!
//! A pool is an open-top box with boundary particles on its floor and sides. The host supplies
//! nearby static block AABBs and dynamic rigid-body AABBs; fluid particles displace against both,
//! return collision impulses to dynamic bodies, and compute hydrostatic buoyancy from displaced volume.
//!
//! Requests (`{"op": ...}`):
//! * `create`    {size:[w,h,d], fill_height, dx, shape: "still"|"dam"} -> {id, particles}
//! * `step`      {id, seconds, max_steps, obstacles?, bodies?} -> {id, steps, advanced, sim_time, body_impulses}
//! * `positions` {id} -> {id, n, xyz:[x,y,z,...]}
//! * `status`    {id} -> metrics
//! * `remove`    {id}
//! * `list`      -> {pools:[id,...]}

use crate::sph::{Aabb, BodyImpulse, Fluid, RigidAabb, V3};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::BTreeMap;

/// Water density (kg/m^3) and speed of sound used by every pool (see `sph::tests`).
const RHO0: f64 = 1000.0;
const SOUND_SPEED: f64 = 12.0;
const GRAVITY: f64 = 9.81;
pub const MAX_PARTICLES: usize = 1024;
pub const MAX_POOLS: usize = 16;
pub const MAX_STEPS_PER_CALL: usize = 400;
const MAX_EXTERNAL_BOXES: usize = 512;
const MAX_COUPLED_BODIES: usize = 64;

fn read_aabbs(v: &Value, key: &str, limit: usize) -> Result<Vec<Aabb>, String> {
    let Some(value) = v.get(key) else {
        return Ok(Vec::new());
    };
    let boxes: Vec<Aabb> =
        serde_json::from_value(value.clone()).map_err(|e| format!("{key}: {e}"))?;
    if boxes.len() > limit {
        return Err(format!("{key} count {} exceeds limit {limit}", boxes.len()));
    }
    if boxes.iter().any(|b| !b.is_valid()) {
        return Err(format!("{key} contains invalid AABB bounds"));
    }
    Ok(boxes)
}

fn read_rigid_bodies(v: &Value) -> Result<Vec<RigidAabb>, String> {
    let Some(value) = v.get("bodies") else {
        return Ok(Vec::new());
    };
    let bodies: Vec<RigidAabb> =
        serde_json::from_value(value.clone()).map_err(|e| format!("bodies: {e}"))?;
    if bodies.len() > MAX_COUPLED_BODIES {
        return Err(format!(
            "bodies count {} exceeds limit {MAX_COUPLED_BODIES}",
            bodies.len()
        ));
    }
    for b in &bodies {
        let valid = b.id > 0
            && (Aabb {
                min: b.min,
                max: b.max,
            })
            .is_valid()
            && b.velocity.iter().all(|x| x.is_finite())
            && b.mass.is_finite()
            && b.mass > 0.0;
        if !valid {
            return Err("bodies contains an invalid id, AABB, velocity, or mass".into());
        }
    }
    Ok(bodies)
}

fn accumulate_impulse(map: &mut BTreeMap<i64, (V3, V3, f64)>, impulse: BodyImpulse) {
    let weight = impulse.impulse.iter().map(|x| x * x).sum::<f64>().sqrt();
    if weight <= 0.0 || !weight.is_finite() {
        return;
    }
    let entry = map.entry(impulse.id).or_insert(([0.0; 3], [0.0; 3], 0.0));
    for k in 0..3 {
        entry.0[k] += impulse.impulse[k];
        entry.1[k] += impulse.point[k] * weight;
    }
    entry.2 += weight;
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CreateSpec {
    size: [f64; 3],
    fill_height: f64,
    dx: f64,
    #[serde(default = "still")]
    shape: String,
}

fn still() -> String {
    "still".into()
}

struct Pool {
    fluid: Fluid,
    size: [f64; 3],
    dx: f64,
    fill_height: f64,
    shape: String,
    sim_time: f64,
    steps: u64,
}

#[derive(Default)]
pub struct FluidWorld {
    next: i64,
    pools: BTreeMap<i64, Pool>,
}

fn id_of(v: &Value) -> Result<i64, String> {
    v.get("id")
        .and_then(Value::as_i64)
        .ok_or_else(|| "missing 'id'".to_string())
}

fn finite3(v: V3) -> bool {
    v.iter().all(|c| c.is_finite())
}

impl FluidWorld {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn exchange(&mut self, request: &str) -> Result<String, String> {
        let v: Value = serde_json::from_str(request).map_err(|e| format!("request JSON: {e}"))?;
        let op = v.get("op").and_then(Value::as_str).ok_or("missing 'op'")?;
        let out = match op {
            "create" => self.create(&v)?,
            "step" => self.step(&v)?,
            "positions" => self.positions(&v)?,
            "status" => self.status(&v)?,
            "remove" => {
                let id = id_of(&v)?;
                self.pools
                    .remove(&id)
                    .ok_or_else(|| format!("no fluid pool {id}"))?;
                json!({ "removed": id })
            }
            "list" => json!({ "pools": self.pools.keys().collect::<Vec<_>>() }),
            other => return Err(format!("unknown fluid op '{other}'")),
        };
        serde_json::to_string(&out).map_err(|e| e.to_string())
    }

    fn create(&mut self, v: &Value) -> Result<Value, String> {
        if self.pools.len() >= MAX_POOLS {
            return Err(format!("too many fluid pools (max {MAX_POOLS})"));
        }
        let mut body = v.clone();
        if let Some(m) = body.as_object_mut() {
            m.remove("op");
        }
        let spec: CreateSpec = serde_json::from_value(body).map_err(|e| format!("create: {e}"))?;
        let [w, h, d] = spec.size;
        for (name, x) in [("size.x", w), ("size.y", h), ("size.z", d)] {
            if !(0.1..=2.0).contains(&x) {
                return Err(format!("{name} must be in 0.1..2.0 m"));
            }
        }
        if !(spec.dx >= 0.02 && spec.dx <= 0.15) {
            return Err("dx must be in 0.02..0.15 m".into());
        }
        if !(spec.fill_height > 0.0 && spec.fill_height <= h) {
            return Err("fill_height must be in (0, size.y]".into());
        }
        let dam = match spec.shape.as_str() {
            "still" => false,
            "dam" => true,
            other => return Err(format!("unknown shape '{other}' (still or dam)")),
        };
        let dx = spec.dx;
        // Lattice of water inside the box; the dam shape keeps a column in the left 40 % with twice the height.
        let x_hi = if dam { w * 0.4 } else { w };
        let top = if dam {
            (2.0 * spec.fill_height).min(h * 0.95)
        } else {
            spec.fill_height
        };
        let lo = [0.5 * dx, 0.0, 0.5 * dx];
        let hi = [(x_hi - 0.5 * dx).max(lo[0]), top, d - 0.5 * dx];
        let fluid = Fluid::lattice(lo, hi, dx, RHO0, SOUND_SPEED, [0.0; 3], [w, h, d]);
        let n = fluid.pos.len();
        if n == 0 || n > MAX_PARTICLES {
            return Err(format!(
                "pool needs {n} particles; limit is {MAX_PARTICLES}"
            ));
        }
        self.next += 1;
        let id = self.next;
        self.pools.insert(
            id,
            Pool {
                fluid,
                size: spec.size,
                dx,
                fill_height: spec.fill_height,
                shape: spec.shape,
                sim_time: 0.0,
                steps: 0,
            },
        );
        Ok(json!({ "id": id, "particles": n }))
    }

    fn pool_mut(&mut self, v: &Value) -> Result<(i64, &mut Pool), String> {
        let id = id_of(v)?;
        let p = self
            .pools
            .get_mut(&id)
            .ok_or_else(|| format!("no fluid pool {id}"))?;
        Ok((id, p))
    }

    fn step(&mut self, v: &Value) -> Result<Value, String> {
        let seconds = v
            .get("seconds")
            .and_then(Value::as_f64)
            .ok_or("missing 'seconds'")?;
        if !(seconds > 0.0 && seconds <= 1.0) {
            return Err("seconds must be in (0, 1]".into());
        }
        let max_steps = v.get("max_steps").and_then(Value::as_u64).unwrap_or(60) as usize;
        let max_steps = max_steps.clamp(1, MAX_STEPS_PER_CALL);
        let mut obstacles = read_aabbs(v, "obstacles", MAX_EXTERNAL_BOXES)?;
        let mut bodies = read_rigid_bodies(v)?;
        let (id, pool) = self.pool_mut(v)?;
        const INTERACTION_RADIUS: f64 = 1.0;
        let near_pool = |min: V3, max: V3| {
            (0..3).all(|k| {
                max[k] >= -INTERACTION_RADIUS && min[k] <= pool.size[k] + INTERACTION_RADIUS
            })
        };
        obstacles.retain(|b| near_pool(b.min, b.max));
        bodies.retain(|b| near_pool(b.min, b.max));
        let mut elapsed = 0.0;
        let mut n = 0usize;
        let mut body_impulses = BTreeMap::new();
        while elapsed < seconds - 1e-12 && n < max_steps {
            let dt = pool.fluid.stable_dt().min(seconds - elapsed);
            if !(dt.is_finite() && dt > 0.0) {
                return Err("fluid time step is not finite".into());
            }
            for impulse in pool.fluid.step_coupled(dt, &obstacles, &bodies) {
                accumulate_impulse(&mut body_impulses, impulse);
            }
            elapsed += dt;
            n += 1;
        }
        if !pool.fluid.pos.iter().all(|p| finite3(*p)) {
            return Err("fluid diverged (non-finite positions)".into());
        }

        // Hydrostatic Archimedes force, based on the pool's conserved water volume. The particle
        // solver separately displaces SPH particles against the supplied rigid AABBs.
        let [width, height, depth] = pool.size;
        let water_level = (pool.fluid.total_mass() / (RHO0 * width * depth)).clamp(0.0, height);
        for body in &bodies {
            let overlap = |lo: f64, hi: f64, water_lo: f64, water_hi: f64| {
                (hi.min(water_hi) - lo.max(water_lo)).max(0.0)
            };
            let dx = overlap(body.min[0], body.max[0], 0.0, width);
            let dy = overlap(body.min[1], body.max[1], 0.0, water_level);
            let dz = overlap(body.min[2], body.max[2], 0.0, depth);
            let volume = dx * dy * dz;
            if volume > 0.0 && elapsed > 0.0 {
                accumulate_impulse(
                    &mut body_impulses,
                    BodyImpulse {
                        id: body.id,
                        impulse: [0.0, RHO0 * GRAVITY * volume * elapsed, 0.0],
                        point: [
                            (body.min[0].max(0.0) + body.max[0].min(width)) * 0.5,
                            (body.min[1].max(0.0) + body.max[1].min(water_level)) * 0.5,
                            (body.min[2].max(0.0) + body.max[2].min(depth)) * 0.5,
                        ],
                    },
                );
            }
        }

        let impulses: Vec<Value> = body_impulses
            .into_iter()
            .map(|(body_id, (impulse, weighted_point, weight))| {
                let point = if weight > 0.0 {
                    [
                        weighted_point[0] / weight,
                        weighted_point[1] / weight,
                        weighted_point[2] / weight,
                    ]
                } else {
                    [0.0; 3]
                };
                json!({ "id": body_id, "impulse": impulse, "point": point })
            })
            .collect();
        pool.sim_time += elapsed;
        pool.steps += n as u64;
        Ok(json!({
            "id": id,
            "steps": n,
            "advanced": elapsed,
            "requested": seconds,
            "sim_time": pool.sim_time,
            "body_impulses": impulses,
        }))
    }

    fn positions(&mut self, v: &Value) -> Result<Value, String> {
        let (id, pool) = self.pool_mut(v)?;
        let mut xyz = Vec::with_capacity(pool.fluid.pos.len() * 3);
        for p in &pool.fluid.pos {
            for c in p {
                xyz.push((c * 1e4).round() / 1e4);
            }
        }
        Ok(json!({ "id": id, "n": pool.fluid.pos.len(), "xyz": xyz }))
    }

    fn status(&mut self, v: &Value) -> Result<Value, String> {
        let (id, pool) = self.pool_mut(v)?;
        let f = &pool.fluid;
        let ke: f64 = f
            .vel
            .iter()
            .map(|v| 0.5 * f.mass * (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]))
            .sum();
        let top = f.pos.iter().map(|p| p[1]).fold(f64::NEG_INFINITY, f64::max);
        Ok(json!({
            "id": id,
            "particles": f.pos.len(),
            "size": pool.size,
            "dx": pool.dx,
            "fill_height": pool.fill_height,
            "shape": pool.shape,
            "mass": f.total_mass(),
            "mean_density": f.mean_density(),
            "kinetic_energy": ke,
            "top": top,
            "sim_time": pool.sim_time,
            "steps": pool.steps,
            "gravity": GRAVITY,
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create(w: &mut FluidWorld, shape: &str) -> i64 {
        let r = w.exchange(&format!(
            r#"{{"op":"create","size":[0.4,0.4,0.4],"fill_height":0.2,"dx":0.05,"shape":"{shape}"}}"#
        ));
        let v: Value = serde_json::from_str(&r.unwrap()).unwrap();
        v["id"].as_i64().unwrap()
    }

    #[test]
    fn still_pool_creates_and_steps_without_losing_water() {
        let mut w = FluidWorld::new();
        let id = create(&mut w, "still");
        let before: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        let mass0 = before["mass"].as_f64().unwrap();
        let r: Value = serde_json::from_str(
            &w.exchange(&format!(
                r#"{{"op":"step","id":{id},"seconds":0.5,"max_steps":2000}}"#
            ))
            .unwrap(),
        )
        .unwrap();
        assert!((r["advanced"].as_f64().unwrap() - 0.5).abs() < 1e-9, "{r}");
        let after: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        assert_eq!(after["mass"].as_f64().unwrap(), mass0);
        assert!(after["kinetic_energy"].as_f64().unwrap().is_finite());
    }

    #[test]
    fn external_block_aabb_displaces_fluid_particles() {
        let mut w = FluidWorld::new();
        let id = create(&mut w, "still");
        let step = format!(
            r#"{{"op":"step","id":{id},"seconds":0.02,"max_steps":60,"obstacles":[{{"min":[0.1,0.0,0.1],"max":[0.3,0.2,0.3]}}]}}"#
        );
        w.exchange(&step).unwrap();
        let positions: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        for p in positions["xyz"].as_array().unwrap().chunks_exact(3) {
            let x = p[0].as_f64().unwrap();
            let y = p[1].as_f64().unwrap();
            let z = p[2].as_f64().unwrap();
            let dx = (0.1 - x).max(0.0).max(x - 0.3);
            let dy = (0.0 - y).max(0.0).max(y - 0.2);
            let dz = (0.1 - z).max(0.0).max(z - 0.3);
            let distance = (dx * dx + dy * dy + dz * dz).sqrt();
            assert!(
                distance + 1e-4 >= 0.025,
                "particle penetrated obstacle: {p:?}, distance {distance}"
            );
        }
    }

    #[test]
    fn rigid_body_gets_archimedes_impulse_from_a_water_pool() {
        let mut w = FluidWorld::new();
        let id = create(&mut w, "still");
        let response: Value = serde_json::from_str(
            &w.exchange(&format!(
                r#"{{"op":"step","id":{id},"seconds":0.05,"max_steps":400,"bodies":[{{"id":7,"min":[0.1,0.05,0.1],"max":[0.3,0.15,0.3],"velocity":[0,0,0],"mass":2.0}}]}}"#
            ))
            .unwrap(),
        )
        .unwrap();
        let impulses = response["body_impulses"].as_array().unwrap();
        let impulse = impulses
            .iter()
            .find(|i| i["id"] == 7)
            .expect("submerged body gets buoyancy");
        assert!(impulse["impulse"][1].as_f64().unwrap() > 0.0, "{impulse}");
        assert!(impulse["point"].as_array().unwrap().len() == 3);
    }

    #[test]
    fn default_bucket_pool_displaces_a_wood_sized_body_and_buoys_it() {
        let mut w = FluidWorld::new();
        let created: Value = serde_json::from_str(
            &w.exchange(r#"{"op":"create","size":[1.2,1.2,1.2],"fill_height":0.6,"dx":0.15,"shape":"still"}"#).unwrap(),
        )
        .unwrap();
        assert_eq!(created["particles"].as_u64(), Some(320));
        let id = created["id"].as_i64().unwrap();
        let response: Value = serde_json::from_str(
            &w.exchange(&format!(
                r#"{{"op":"step","id":{id},"seconds":0.05,"max_steps":60,"bodies":[{{"id":42,"min":[0.1,0.1,0.1],"max":[1.1,1.1,1.1],"velocity":[0,0,0],"mass":550.0}}]}}"#
            ))
            .unwrap(),
        )
        .unwrap();
        let impulse = response["body_impulses"]
            .as_array()
            .unwrap()
            .iter()
            .find(|i| i["id"] == 42)
            .expect("immersed body should receive buoyancy");
        let buoyancy = impulse["impulse"][1].as_f64().unwrap();
        assert!(
            buoyancy > 550.0 * 9.81 * 0.05,
            "buoyancy {buoyancy} must overcome one tick of body weight"
        );

        let positions: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        for p in positions["xyz"].as_array().unwrap().chunks_exact(3) {
            let x = p[0].as_f64().unwrap();
            let y = p[1].as_f64().unwrap();
            let z = p[2].as_f64().unwrap();
            let dx = (0.1 - x).max(0.0).max(x - 1.1);
            let dy = (0.1 - y).max(0.0).max(y - 1.1);
            let dz = (0.1 - z).max(0.0).max(z - 1.1);
            assert!(
                (dx * dx + dy * dy + dz * dz).sqrt() + 1e-3 >= 0.075,
                "particle overlaps rigid body: {p:?}"
            );
        }
    }

    #[test]
    fn bad_external_collision_input_is_rejected() {
        let mut w = FluidWorld::new();
        let id = create(&mut w, "still");
        let bad = format!(
            r#"{{"op":"step","id":{id},"seconds":0.05,"obstacles":[{{"min":[0,0,0],"max":[0,1,1]}}]}}"#
        );
        assert!(w.exchange(&bad).unwrap_err().contains("invalid AABB"));
        let bad = format!(
            r#"{{"op":"step","id":{id},"seconds":0.05,"bodies":[{{"id":0,"min":[0,0,0],"max":[1,1,1],"velocity":[0,0,0],"mass":1}}]}}"#
        );
        assert!(w.exchange(&bad).unwrap_err().contains("invalid id"));
    }

    #[test]
    fn dam_pool_has_fewer_particles_and_positions_are_flat_xyz() {
        let mut w = FluidWorld::new();
        let still = create(&mut w, "still");
        let dam = create(&mut w, "dam");
        let n_still = serde_json::from_str::<Value>(
            &w.exchange(&format!(r#"{{"op":"status","id":{still}}}"#))
                .unwrap(),
        )
        .unwrap()["particles"]
            .as_u64()
            .unwrap();
        let n_dam = serde_json::from_str::<Value>(
            &w.exchange(&format!(r#"{{"op":"status","id":{dam}}}"#))
                .unwrap(),
        )
        .unwrap()["particles"]
            .as_u64()
            .unwrap();
        assert!(n_dam < n_still, "dam {n_dam} still {n_still}");
        let p: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{dam}}}"#))
                .unwrap(),
        )
        .unwrap();
        assert_eq!(p["xyz"].as_array().unwrap().len() as u64, n_dam * 3);
    }

    #[test]
    fn bad_requests_are_rejected() {
        let mut w = FluidWorld::new();
        assert!(w
            .exchange(r#"{"op":"create","size":[0.4,0.4,0.4],"fill_height":0.2,"dx":0.01}"#)
            .is_err());
        assert!(w
            .exchange(
                r#"{"op":"create","size":[0.4,0.4,0.4],"fill_height":0.2,"dx":0.05,"shape":"x"}"#
            )
            .is_err());
        assert!(w
            .exchange(r#"{"op":"create","size":[9,0.4,0.4],"fill_height":0.2,"dx":0.05}"#)
            .is_err());
        assert!(w
            .exchange(r#"{"op":"step","id":99,"seconds":0.1}"#)
            .is_err());
        assert!(w.exchange(r#"{"op":"nope"}"#).is_err());
    }

    /// Cost of one 0.05 s tick for the game pool geometries (320 and 600 particles).
    /// Run: cargo test --release --lib bench_tick -- --ignored --nocapture
    #[test]
    #[ignore]
    fn bench_tick() {
        let mut w = FluidWorld::new();
        for dx in [0.15, 0.12] {
            let id = w.exchange(&format!(r#"{{"op":"create","size":[1.2,1.2,1.2],"fill_height":0.6,"dx":{dx},"shape":"still"}}"#)).unwrap();
            let id: Value = serde_json::from_str(&id).unwrap();
            let id = id["id"].as_i64().unwrap();
            for _ in 0..10 {
                w.exchange(&format!(
                    r#"{{"op":"step","id":{id},"seconds":0.05,"max_steps":60}}"#
                ))
                .unwrap();
            }
            let timed_ticks = 100;
            let t = std::time::Instant::now();
            let mut steps = 0;
            for _ in 0..timed_ticks {
                let r: Value = serde_json::from_str(
                    &w.exchange(&format!(
                        r#"{{"op":"step","id":{id},"seconds":0.05,"max_steps":60}}"#
                    ))
                    .unwrap(),
                )
                .unwrap();
                steps += r["steps"].as_u64().unwrap();
            }
            let ms = t.elapsed().as_secs_f64() * 1000.0 / timed_ticks as f64;
            let st: Value = serde_json::from_str(
                &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                    .unwrap(),
            )
            .unwrap();
            let expected = if dx == 0.15 { 320 } else { 600 };
            let budget_ms = if expected == 320 { 15.0 } else { 30.0 };
            let particles = st["particles"].as_u64().unwrap();
            println!("dx {dx}: {particles} particles, {ms:.1} ms per 0.05 s tick ({} substeps/tick, warm-up excluded), ke {:.3}", steps / timed_ticks, st["kinetic_energy"]);
            assert_eq!(particles, expected);
            assert!(ms < budget_ms, "SPH budget missed: {particles} particles cost {ms:.2} ms (limit {budget_ms:.1} ms)");
        }
    }
}
