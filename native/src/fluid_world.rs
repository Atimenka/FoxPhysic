//! Pools of 3D SPH water (see `sph.rs`), driven through JSON requests like `soft_world.rs`.
//!
//! A pool is an open-top box with boundary particles on its floor and sides. Its water does not yet
//! exchange forces with rigid bodies; it is a self-contained fluid for presentation and tests.
//!
//! Requests (`{"op": ...}`):
//! * `create`   {size:[w,h,d], fill_height, dx, shape: "still"|"dam"} -> {id, particles}
//! * `step`     {id, seconds, max_steps} -> {id, steps, advanced, sim_time}
//! * `positions`{id} -> {id, n, xyz:[x,y,z,...]}
//! * `status`   {id} -> metrics
//! * `remove`   {id}
//! * `list`     -> {pools:[id,...]}

use crate::sph::{Fluid, V3};
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
    v.get("id").and_then(Value::as_i64).ok_or_else(|| "missing 'id'".to_string())
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
                self.pools.remove(&id).ok_or_else(|| format!("no fluid pool {id}"))?;
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
        if !(spec.dx >= 0.02 && spec.dx <= 0.1) {
            return Err("dx must be in 0.02..0.1 m".into());
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
        let top = if dam { (2.0 * spec.fill_height).min(h * 0.95) } else { spec.fill_height };
        let lo = [0.5 * dx, 0.0, 0.5 * dx];
        let hi = [(x_hi - 0.5 * dx).max(lo[0]), top, d - 0.5 * dx];
        let fluid = Fluid::lattice(lo, hi, dx, RHO0, SOUND_SPEED, [0.0; 3], [w, h, d]);
        let n = fluid.pos.len();
        if n == 0 || n > MAX_PARTICLES {
            return Err(format!("pool needs {n} particles; limit is {MAX_PARTICLES}"));
        }
        self.next += 1;
        let id = self.next;
        self.pools.insert(
            id,
            Pool { fluid, size: spec.size, dx, fill_height: spec.fill_height, shape: spec.shape, sim_time: 0.0, steps: 0 },
        );
        Ok(json!({ "id": id, "particles": n }))
    }

    fn pool_mut(&mut self, v: &Value) -> Result<(i64, &mut Pool), String> {
        let id = id_of(v)?;
        let p = self.pools.get_mut(&id).ok_or_else(|| format!("no fluid pool {id}"))?;
        Ok((id, p))
    }

    fn step(&mut self, v: &Value) -> Result<Value, String> {
        let seconds = v.get("seconds").and_then(Value::as_f64).ok_or("missing 'seconds'")?;
        if !(seconds > 0.0 && seconds <= 1.0) {
            return Err("seconds must be in (0, 1]".into());
        }
        let max_steps = v.get("max_steps").and_then(Value::as_u64).unwrap_or(60) as usize;
        let max_steps = max_steps.clamp(1, MAX_STEPS_PER_CALL);
        let (id, pool) = self.pool_mut(v)?;
        let mut elapsed = 0.0;
        let mut n = 0usize;
        while elapsed < seconds - 1e-12 && n < max_steps {
            let dt = pool.fluid.stable_dt().min(seconds - elapsed);
            if !(dt.is_finite() && dt > 0.0) {
                return Err("fluid time step is not finite".into());
            }
            pool.fluid.step(dt);
            elapsed += dt;
            n += 1;
        }
        if !pool.fluid.pos.iter().all(|p| finite3(*p)) {
            return Err("fluid diverged (non-finite positions)".into());
        }
        pool.sim_time += elapsed;
        pool.steps += n as u64;
        Ok(json!({ "id": id, "steps": n, "advanced": elapsed, "requested": seconds, "sim_time": pool.sim_time }))
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
        let ke: f64 = f.vel.iter().map(|v| 0.5 * f.mass * (v[0] * v[0] + v[1] * v[1] + v[2] * v[2])).sum();
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
        let before: Value = serde_json::from_str(&w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#)).unwrap()).unwrap();
        let mass0 = before["mass"].as_f64().unwrap();
        let r: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"step","id":{id},"seconds":0.5,"max_steps":2000}}"#)).unwrap(),
        )
        .unwrap();
        assert!((r["advanced"].as_f64().unwrap() - 0.5).abs() < 1e-9, "{r}");
        let after: Value = serde_json::from_str(&w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#)).unwrap()).unwrap();
        assert_eq!(after["mass"].as_f64().unwrap(), mass0);
        assert!(after["kinetic_energy"].as_f64().unwrap().is_finite());
    }

    #[test]
    fn dam_pool_has_fewer_particles_and_positions_are_flat_xyz() {
        let mut w = FluidWorld::new();
        let still = create(&mut w, "still");
        let dam = create(&mut w, "dam");
        let n_still = serde_json::from_str::<Value>(&w.exchange(&format!(r#"{{"op":"status","id":{still}}}"#)).unwrap()).unwrap()["particles"].as_u64().unwrap();
        let n_dam = serde_json::from_str::<Value>(&w.exchange(&format!(r#"{{"op":"status","id":{dam}}}"#)).unwrap()).unwrap()["particles"].as_u64().unwrap();
        assert!(n_dam < n_still, "dam {n_dam} still {n_still}");
        let p: Value = serde_json::from_str(&w.exchange(&format!(r#"{{"op":"positions","id":{dam}}}"#)).unwrap()).unwrap();
        assert_eq!(p["xyz"].as_array().unwrap().len() as u64, n_dam * 3);
    }

    #[test]
    fn bad_requests_are_rejected() {
        let mut w = FluidWorld::new();
        assert!(w.exchange(r#"{"op":"create","size":[0.4,0.4,0.4],"fill_height":0.2,"dx":0.01}"#).is_err());
        assert!(w.exchange(r#"{"op":"create","size":[0.4,0.4,0.4],"fill_height":0.2,"dx":0.05,"shape":"x"}"#).is_err());
        assert!(w.exchange(r#"{"op":"create","size":[9,0.4,0.4],"fill_height":0.2,"dx":0.05}"#).is_err());
        assert!(w.exchange(r#"{"op":"step","id":99,"seconds":0.1}"#).is_err());
        assert!(w.exchange(r#"{"op":"nope"}"#).is_err());
    }

    /// Cost of one 0.05 s server tick for a bucket-sized pool. Run: cargo test --release --lib bench_tick -- --ignored --nocapture
    #[test]
    #[ignore]
    fn bench_tick() {
        let mut w = FluidWorld::new();
        for (shape, dx) in [("still", 0.05), ("still", 0.04)] {
            let id = w.exchange(&format!(r#"{{"op":"create","size":[0.4,0.4,0.4],"fill_height":0.2,"dx":{dx},"shape":"{shape}"}}"#)).unwrap();
            let id: Value = serde_json::from_str(&id).unwrap();
            let id = id["id"].as_i64().unwrap();
            let t = std::time::Instant::now();
            let mut steps = 0;
            for _ in 0..20 {
                let r: Value = serde_json::from_str(&w.exchange(&format!(r#"{{"op":"step","id":{id},"seconds":0.05,"max_steps":400}}"#)).unwrap()).unwrap();
                steps += r["steps"].as_u64().unwrap();
            }
            let ms = t.elapsed().as_secs_f64() * 1000.0 / 20.0;
            let st: Value = serde_json::from_str(&w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#)).unwrap()).unwrap();
            println!("dx {dx}: {} particles, {:.1} ms per 0.05 s tick ({} substeps/tick), ke {:.3}", st["particles"], ms, steps / 20, st["kinetic_energy"]);
        }
    }
}
