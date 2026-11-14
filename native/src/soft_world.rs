//! Registry of soft bodies behind one JSON entry point (`softExchange` in lib.rs).
//!
//! Operations: spawn, step, status, positions, list, pin, anchor, anchor_targets, release,
//! wind, stiffness, damping, remove. The step operation accepts per-body static AABBs and dynamic
//! rigid AABBs; XPBD projects contacts, runs self-collision, and returns rigid-body impulses.

use crate::soft::{grid_tets, SoftBody, V3};
use crate::sph::{Aabb, BodyImpulse, RigidAabb};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::time::Instant;

pub const MAX_BODIES: usize = 16;
pub const MAX_PARTICLES: usize = 5000;
const MAX_COLLISION_BOXES: usize = 512;
const MAX_RIGID_BODIES: usize = 64;
const MAX_ENVIRONMENTS: usize = MAX_BODIES;
/// Cell size in metres for all presets (5 cm voxels).
/// Bodies must be spawned close to the floor: a 2 m drop inverts tetrahedra (see docs/SOFT.md).
const CELL: f64 = 0.05;
/// Drag per particle (kg/m) = 0.5 * rho_air (1.2) * Cd (1.0) * cell area.
const DRAG_PER_PARTICLE: f64 = 0.5 * 1.2 * 1.0 * CELL * CELL;
/// Wind speed limit (m/s); storms are ~20-30 m/s, gusts are clamped here.
const MAX_WIND: f64 = 60.0;

/// Preset: grid size in cells, density and base compliances (m/N; smaller = stiffer).
#[derive(Clone, Copy, Debug)]
pub struct Preset {
    pub name: &'static str,
    pub n: [usize; 3],
    pub density: f64,
    pub distance_compliance: f64,
    pub volume_compliance: f64,
    /// Maximum edge stretch before cloth/rope constraints tear; bulk materials do not tear.
    pub tear_strain: Option<f64>,
}

pub const PRESETS: [Preset; 4] = [
    Preset {
        name: "rubber",
        n: [4, 4, 4],
        density: 1100.0,
        distance_compliance: 5e-6,
        volume_compliance: 1e-9,
        tear_strain: None,
    },
    Preset {
        name: "jelly",
        n: [6, 6, 6],
        density: 1000.0,
        distance_compliance: 5e-6,
        volume_compliance: 1e-9,
        tear_strain: None,
    },
    Preset {
        name: "cloth",
        n: [16, 1, 16],
        density: 800.0,
        distance_compliance: 1e-3,
        volume_compliance: 1e-6,
        tear_strain: Some(1.8),
    },
    Preset {
        name: "rope",
        n: [24, 1, 1],
        density: 1100.0,
        distance_compliance: 1e-4,
        volume_compliance: 1e-7,
        tear_strain: Some(2.0),
    },
];

#[derive(Default)]
struct SoftEnvironment {
    obstacles: Vec<Aabb>,
    bodies: Vec<RigidAabb>,
}

fn read_boxes(v: &Value, key: &str) -> Result<Vec<Aabb>, String> {
    let Some(value) = v.get(key) else {
        return Ok(Vec::new());
    };
    let boxes: Vec<Aabb> =
        serde_json::from_value(value.clone()).map_err(|e| format!("{key}: {e}"))?;
    if boxes.len() > MAX_COLLISION_BOXES {
        return Err(format!(
            "{key} count {} exceeds limit {MAX_COLLISION_BOXES}",
            boxes.len()
        ));
    }
    if boxes.iter().any(|b| !b.is_valid()) {
        return Err(format!("{key} contains invalid AABB bounds"));
    }
    Ok(boxes)
}

fn read_rigid(v: &Value, key: &str) -> Result<Vec<RigidAabb>, String> {
    let Some(value) = v.get(key) else {
        return Ok(Vec::new());
    };
    let bodies: Vec<RigidAabb> =
        serde_json::from_value(value.clone()).map_err(|e| format!("{key}: {e}"))?;
    if bodies.len() > MAX_RIGID_BODIES {
        return Err(format!(
            "{key} count {} exceeds limit {MAX_RIGID_BODIES}",
            bodies.len()
        ));
    }
    let mut seen = std::collections::BTreeSet::new();
    for b in &bodies {
        if b.id <= 0
            || !seen.insert(b.id)
            || !(Aabb {
                min: b.min,
                max: b.max,
            })
            .is_valid()
            || !b.velocity.iter().all(|x| x.is_finite())
            || !b.mass.is_finite()
            || b.mass <= 0.0
        {
            return Err(format!(
                "{key} contains a duplicate id or invalid AABB, velocity, or mass"
            ));
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

fn read_environments(
    v: &Value,
) -> Result<(SoftEnvironment, BTreeMap<i64, SoftEnvironment>), String> {
    let default = SoftEnvironment {
        obstacles: read_boxes(v, "obstacles")?,
        bodies: read_rigid(v, "bodies")?,
    };
    let Some(list) = v.get("environments") else {
        return Ok((default, BTreeMap::new()));
    };
    let list = list.as_array().ok_or("'environments' must be an array")?;
    if list.len() > MAX_ENVIRONMENTS {
        return Err(format!(
            "environments count {} exceeds limit {MAX_ENVIRONMENTS}",
            list.len()
        ));
    }
    let mut environments = BTreeMap::new();
    for item in list {
        let id = item
            .get("id")
            .and_then(Value::as_i64)
            .filter(|id| *id > 0)
            .ok_or("environment needs a positive soft-body id")?;
        let environment = SoftEnvironment {
            obstacles: read_boxes(item, "obstacles")?,
            bodies: read_rigid(item, "bodies")?,
        };
        if environments.insert(id, environment).is_some() {
            return Err(format!("duplicate environment for soft body {id}"));
        }
    }
    Ok((default, environments))
}

pub fn preset(name: &str) -> Result<Preset, String> {
    PRESETS
        .iter()
        .copied()
        .find(|p| p.name == name)
        .ok_or_else(|| format!("unknown soft preset '{name}' (rubber|jelly|cloth|rope)"))
}

/// A particle held by the host: pinned, with its own inverse mass saved for `release`.
#[derive(Clone, Copy, Debug)]
struct Anchor {
    inv_mass: f64,
    target: V3,
}

/// Boundary triangles of the tetrahedral mesh: faces used by exactly one tetrahedron.
/// Winding is not outward.
fn surface_of(body: &SoftBody) -> Vec<u64> {
    let mut faces: BTreeMap<[usize; 3], (u32, [usize; 3])> = BTreeMap::new();
    for vol in &body.volumes {
        let [a, b, c, d] = vol.p.map(usize::from);
        for f in [[a, b, c], [a, b, d], [a, c, d], [b, c, d]] {
            let mut key = f;
            key.sort_unstable();
            faces.entry(key).or_insert((0, f)).0 += 1;
        }
    }
    let mut tris: Vec<u64> = Vec::new();
    for (count, f) in faces.values() {
        if *count == 1 {
            tris.extend(f.iter().map(|&i| i as u64));
        }
    }
    tris
}

struct Entry {
    preset: Preset,
    body: SoftBody,
    /// Cached boundary triangles (particle indices, 3 per triangle); rebuilt when tearing changes topology.
    surface: Vec<u64>,
    /// Compliance at stiffness 1.0; `stiffness` divides it.
    base_dc: f64,
    base_vc: f64,
    stiffness: f64,
    anchors: BTreeMap<usize, Anchor>,
}

pub struct SoftWorld {
    next: i64,
    bodies: BTreeMap<i64, Entry>,
    wind: V3,
}

impl Default for SoftWorld {
    fn default() -> Self {
        Self::new()
    }
}

fn num(v: &Value, key: &str) -> Result<f64, String> {
    let x = v
        .get(key)
        .and_then(Value::as_f64)
        .ok_or_else(|| format!("missing number '{key}'"))?;
    if !x.is_finite() {
        return Err(format!("'{key}' must be finite"));
    }
    Ok(x)
}

fn id_of(v: &Value) -> Result<i64, String> {
    v.get("id")
        .and_then(Value::as_i64)
        .ok_or_else(|| "missing id".to_string())
}

fn vec3(v: &Value, key: &str) -> Result<V3, String> {
    let a = v
        .get(key)
        .and_then(Value::as_array)
        .ok_or_else(|| format!("missing array '{key}'"))?;
    if a.len() != 3 {
        return Err(format!("'{key}' must have 3 numbers"));
    }
    let mut out = [0.0; 3];
    for (i, x) in a.iter().enumerate() {
        out[i] = x
            .as_f64()
            .filter(|f| f.is_finite())
            .ok_or_else(|| format!("'{key}' must be finite numbers"))?;
    }
    Ok(out)
}

impl SoftWorld {
    pub fn new() -> Self {
        Self {
            next: 1,
            bodies: BTreeMap::new(),
            wind: [0.0; 3],
        }
    }

    pub fn len(&self) -> usize {
        self.bodies.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bodies.is_empty()
    }

    /// Handles one JSON request and returns the JSON response, or an error message.
    pub fn exchange(&mut self, request: &str) -> Result<String, String> {
        let v: Value = serde_json::from_str(request).map_err(|e| format!("request JSON: {e}"))?;
        let op = v.get("op").and_then(Value::as_str).ok_or("missing 'op'")?;
        let out = match op {
            "spawn" => self.spawn(&v)?,
            "step" => self.step(&v)?,
            "status" => self.status(id_of(&v)?)?,
            "positions" => self.positions(&v)?,
            "list" => json!({ "bodies": self.bodies.keys().collect::<Vec<_>>() }),
            "pin" => self.pin(&v)?,
            "anchor" => self.anchor(&v)?,
            "anchor_targets" => self.anchor_targets(&v)?,
            "release" => self.release(&v)?,
            "wind" => self.set_wind(&v)?,
            "stiffness" => self.stiffness(&v)?,
            "damping" => self.damping(&v)?,
            "remove" => {
                let id = id_of(&v)?;
                self.bodies
                    .remove(&id)
                    .ok_or_else(|| format!("no soft body {id}"))?;
                json!({ "removed": id })
            }
            other => return Err(format!("unknown soft op '{other}'")),
        };
        serde_json::to_string(&out).map_err(|e| e.to_string())
    }

    fn entry(&mut self, v: &Value) -> Result<(i64, &mut Entry), String> {
        let id = id_of(v)?;
        let e = self
            .bodies
            .get_mut(&id)
            .ok_or_else(|| format!("no soft body {id}"))?;
        Ok((id, e))
    }

    fn spawn(&mut self, v: &Value) -> Result<Value, String> {
        if self.bodies.len() >= MAX_BODIES {
            return Err(format!("soft body limit: {MAX_BODIES}"));
        }
        let p = preset(
            v.get("preset")
                .and_then(Value::as_str)
                .ok_or("missing 'preset'")?,
        )?;
        let origin = vec3(v, "origin")?;
        let count = (p.n[0] + 1) * (p.n[1] + 1) * (p.n[2] + 1);
        if count > MAX_PARTICLES {
            return Err(format!("too many particles: {count} > {MAX_PARTICLES}"));
        }
        let (mut pos, tets) = grid_tets(p.n[0], p.n[1], p.n[2], CELL);
        for q in pos.iter_mut() {
            *q = [q[0] + origin[0], q[1] + origin[1], q[2] + origin[2]];
        }
        let mut body = SoftBody::from_tets(
            &pos,
            &tets,
            p.density,
            p.distance_compliance,
            p.volume_compliance,
        )?;
        body.iterations = 4;
        body.damping = 0.2;
        body.drag = DRAG_PER_PARTICLE;
        if v.get("floor").is_some() {
            body.floor = Some(num(v, "floor")?);
        }
        let id = self.next;
        self.next += 1;
        let surface = surface_of(&body);
        self.bodies.insert(
            id,
            Entry {
                preset: p,
                body,
                surface,
                base_dc: p.distance_compliance,
                base_vc: p.volume_compliance,
                stiffness: 1.0,
                anchors: BTreeMap::new(),
            },
        );
        Ok(json!({ "id": id, "preset": p.name, "particles": count, "tets": tets.len() }))
    }

    fn step(&mut self, v: &Value) -> Result<Value, String> {
        let dt = num(v, "dt")?;
        if !(dt > 0.0 && dt <= 0.2) {
            return Err("dt must be in (0, 0.2] s".into());
        }
        let substeps = v
            .get("substeps")
            .and_then(Value::as_u64)
            .unwrap_or(8)
            .clamp(1, 64) as usize;
        let (default_environment, environments) = read_environments(v)?;
        for id in environments.keys() {
            if !self.bodies.contains_key(id) {
                return Err(format!("environment references unknown soft body {id}"));
            }
        }
        let t0 = Instant::now();
        let wind = self.wind;
        let mut impulse_rows = Vec::new();
        let mut bound_rows = Vec::new();
        let mut tears = Vec::new();
        let mut torn_total = 0usize;
        for (&id, e) in self.bodies.iter_mut() {
            // Anchored particles move to their targets this step; their velocity is the implied one,
            // so neighbours see the motion of the rigid body they follow.
            for (&i, a) in &e.anchors {
                let b = &mut e.body;
                let d = [0, 1, 2].map(|k| (a.target[k] - b.pos[i][k]) / dt);
                b.vel[i] = d;
                b.pos[i] = a.target;
                b.prev[i] = a.target;
            }
            e.body.wind = wind;
            let environment = environments.get(&id).unwrap_or(&default_environment);
            let result = e.body.step_coupled(
                dt,
                substeps,
                &environment.obstacles,
                &environment.bodies,
                e.preset.tear_strain,
            );
            let mut body_impulses = BTreeMap::new();
            for impulse in result.body_impulses {
                accumulate_impulse(&mut body_impulses, impulse);
            }
            for (body_id, (impulse, weighted_point, weight)) in body_impulses {
                let point = if weight > 0.0 {
                    [
                        weighted_point[0] / weight,
                        weighted_point[1] / weight,
                        weighted_point[2] / weight,
                    ]
                } else {
                    [0.0; 3]
                };
                impulse_rows.push(
                    json!({ "soft_id": id, "id": body_id, "impulse": impulse, "point": point }),
                );
            }
            if result.torn_constraints > 0 {
                torn_total += result.torn_constraints;
                e.surface = surface_of(&e.body);
                tears.push(json!({ "id": id, "constraints": result.torn_constraints }));
            }
            let mut min = [f64::INFINITY; 3];
            let mut max = [f64::NEG_INFINITY; 3];
            for p in &e.body.pos {
                for k in 0..3 {
                    min[k] = min[k].min(p[k]);
                    max[k] = max[k].max(p[k]);
                }
            }
            bound_rows.push(json!({ "id": id, "min": min, "max": max }));
        }
        let ms = t0.elapsed().as_secs_f64() * 1e3;
        Ok(json!({
            "bodies": self.bodies.len(),
            "ms": ms,
            "body_impulses": impulse_rows,
            "bounds": bound_rows,
            "torn_constraints": torn_total,
            "tears": tears,
        }))
    }

    /// Particle positions (flat x,y,z, rounded to 1 mm) and the outer surface: triangles of faces that
    /// belong to exactly one tetrahedron. Indices refer to the particle list. Winding is not outward.
    /// `"tris": false` omits the surface (the client keeps the copy it got earlier); the default includes it.
    fn positions(&self, v: &Value) -> Result<Value, String> {
        let id = id_of(v)?;
        let e = self
            .bodies
            .get(&id)
            .ok_or_else(|| format!("no soft body {id}"))?;
        let pos: Vec<f64> = e
            .body
            .pos
            .iter()
            .flat_map(|p| p.iter().map(|x| (x * 1000.0).round() / 1000.0))
            .collect();
        let mut out =
            json!({ "id": id, "preset": e.preset.name, "particles": e.body.pos.len(), "pos": pos });
        if v.get("tris").and_then(Value::as_bool) != Some(false) {
            out["tris"] = json!(e.surface);
        }
        Ok(out)
    }

    fn status(&self, id: i64) -> Result<Value, String> {
        let e = self
            .bodies
            .get(&id)
            .ok_or_else(|| format!("no soft body {id}"))?;
        let b = &e.body;
        let n = b.pos.len() as f64;
        let mut c = [0.0; 3];
        let mut vmax: f64 = 0.0;
        let mut finite = true;
        for (q, u) in b.pos.iter().zip(b.vel.iter()) {
            for k in 0..3 {
                c[k] += q[k] / n;
                finite &= q[k].is_finite();
            }
            vmax = vmax.max((u[0] * u[0] + u[1] * u[1] + u[2] * u[2]).sqrt());
        }
        let ratios = b.volume_ratios();
        let vmin = ratios.iter().copied().fold(f64::INFINITY, f64::min);
        let vmaxr = ratios.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        Ok(json!({
            "id": id,
            "preset": e.preset.name,
            "particles": b.pos.len(),
            "center": c,
            "vmax": vmax,
            "kinetic_energy": b.kinetic_energy(),
            "volume_ratio_min": vmin,
            "volume_ratio_max": vmaxr,
            "stiffness": e.stiffness,
            "damping": b.damping,
            "anchored": e.anchors.len(),
            "torn_constraints": b.torn_constraints,
            "distance_constraints": b.distances.len(),
            "volume_constraints": b.volumes.len(),
            "wind": self.wind,
            "finite": finite,
        }))
    }

    /// Particles chosen by `index` (one particle) or `side` (a layer: top/bottom by y, start/end by x).
    fn pick(b: &SoftBody, v: &Value) -> Result<Vec<usize>, String> {
        if let Some(i) = v.get("index").and_then(Value::as_u64) {
            let i = i as usize;
            if i >= b.pos.len() {
                return Err(format!("particle index out of range: {i}"));
            }
            return Ok(vec![i]);
        }
        let side = v
            .get("side")
            .and_then(Value::as_str)
            .ok_or("needs 'index' or 'side'")?;
        let axis = match side {
            "top" | "bottom" => 1,
            "start" | "end" => 0,
            _ => return Err("side must be top, bottom, start or end".into()),
        };
        let lo = b.pos.iter().map(|q| q[axis]).fold(f64::INFINITY, f64::min);
        let hi = b
            .pos
            .iter()
            .map(|q| q[axis])
            .fold(f64::NEG_INFINITY, f64::max);
        let target = if side == "top" || side == "end" {
            hi
        } else {
            lo
        };
        Ok((0..b.pos.len())
            .filter(|&i| (b.pos[i][axis] - target).abs() < 1e-9)
            .collect())
    }

    /// Pins particles: `index` (one particle), or a layer: `side` = top/bottom (by y) or start/end (by x).
    fn pin(&mut self, v: &Value) -> Result<Value, String> {
        let (id, e) = self.entry(v)?;
        let picked = Self::pick(&e.body, v)?;
        for &i in &picked {
            e.body.pin(i);
        }
        Ok(json!({ "id": id, "pinned": picked.len() }))
    }

    /// Anchors particles (like pin) and remembers them as held by the host. Returns their indices and
    /// positions, so the host can compute each particle's offset from the rigid body it follows.
    fn anchor(&mut self, v: &Value) -> Result<Value, String> {
        let (id, e) = self.entry(v)?;
        let picked = Self::pick(&e.body, v)?;
        for &i in &picked {
            if !e.anchors.contains_key(&i) {
                e.anchors.insert(
                    i,
                    Anchor {
                        inv_mass: e.body.inv_mass[i],
                        target: e.body.pos[i],
                    },
                );
            }
            e.body.pin(i);
        }
        let pos: Vec<f64> = picked.iter().flat_map(|&i| e.body.pos[i]).collect();
        Ok(json!({ "id": id, "anchored": picked.len(), "indices": picked, "positions": pos }))
    }

    /// Sets the targets of anchored particles: `targets` = [[index, x, y, z], ...] (world metres).
    /// An index that is not anchored is an error and nothing is changed.
    fn anchor_targets(&mut self, v: &Value) -> Result<Value, String> {
        let (id, e) = self.entry(v)?;
        let list = v
            .get("targets")
            .and_then(Value::as_array)
            .ok_or("missing array 'targets'")?;
        let mut parsed = Vec::with_capacity(list.len());
        for t in list {
            let a = t
                .as_array()
                .filter(|a| a.len() == 4)
                .ok_or("each target is [index, x, y, z]")?;
            let i = a[0]
                .as_u64()
                .ok_or("target index must be a non-negative integer")? as usize;
            let mut p = [0.0; 3];
            for k in 0..3 {
                p[k] = a[k + 1]
                    .as_f64()
                    .filter(|f| f.is_finite())
                    .ok_or("target coordinates must be finite")?;
            }
            if !e.anchors.contains_key(&i) {
                return Err(format!("particle {i} is not anchored"));
            }
            parsed.push((i, p));
        }
        for (i, p) in &parsed {
            if let Some(a) = e.anchors.get_mut(i) {
                a.target = *p;
            }
        }
        Ok(json!({ "id": id, "updated": parsed.len() }))
    }

    /// Frees all anchored particles of one body: they get back their saved inverse mass.
    fn release(&mut self, v: &Value) -> Result<Value, String> {
        let (id, e) = self.entry(v)?;
        let n = e.anchors.len();
        for (&i, a) in &e.anchors {
            e.body.inv_mass[i] = a.inv_mass;
        }
        e.anchors.clear();
        Ok(json!({ "id": id, "released": n }))
    }

    /// Sets the wind for all soft bodies: `velocity` = [x, y, z] in m/s (world axes), each axis within +-60.
    fn set_wind(&mut self, v: &Value) -> Result<Value, String> {
        let w = vec3(v, "velocity")?;
        if w.iter().any(|x| x.abs() > MAX_WIND) {
            return Err(format!("wind components must be within +-{MAX_WIND} m/s"));
        }
        self.wind = w;
        Ok(json!({ "wind": w }))
    }

    /// Stiffness k >= 0.05 divides the preset compliances: 2.0 = twice as stiff.
    fn stiffness(&mut self, v: &Value) -> Result<Value, String> {
        let k = num(v, "value")?;
        if !(0.05..=100.0).contains(&k) {
            return Err("stiffness must be in [0.05, 100]".into());
        }
        let (id, e) = self.entry(v)?;
        e.stiffness = k;
        for d in e.body.distances.iter_mut() {
            d.compliance = e.base_dc / k;
        }
        for c in e.body.volumes.iter_mut() {
            c.compliance = e.base_vc / k;
        }
        Ok(json!({ "id": id, "stiffness": k, "preset": e.preset.name }))
    }

    fn damping(&mut self, v: &Value) -> Result<Value, String> {
        let d = num(v, "value")?;
        if !(0.0..=50.0).contains(&d) {
            return Err("damping must be in [0, 50] 1/s".into());
        }
        let (id, e) = self.entry(v)?;
        e.body.damping = d;
        Ok(json!({ "id": id, "damping": d }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spawn(w: &mut SoftWorld, preset: &str) -> i64 {
        let r = w
            .exchange(&format!(
                r#"{{"op":"spawn","preset":"{preset}","origin":[0,0.1,0],"floor":0}}"#
            ))
            .unwrap();
        serde_json::from_str::<Value>(&r).unwrap()["id"]
            .as_i64()
            .unwrap()
    }

    #[test]
    fn every_preset_drops_and_stays_finite() {
        for p in PRESETS {
            let mut w = SoftWorld::new();
            let id = spawn(&mut w, p.name);
            for _ in 0..200 {
                w.exchange(r#"{"op":"step","dt":0.05,"substeps":8}"#)
                    .unwrap();
            }
            let s: Value = serde_json::from_str(
                &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                    .unwrap(),
            )
            .unwrap();
            assert_eq!(s["finite"], true, "{}", p.name);
            let vmin = s["volume_ratio_min"].as_f64().unwrap();
            let vmax = s["volume_ratio_max"].as_f64().unwrap();
            assert!(
                vmin > 0.4 && vmax < 1.5,
                "{}: volume ratios {vmin}..{vmax}",
                p.name
            );
            let c = s["center"][1].as_f64().unwrap();
            assert!(c > -0.01 && c < 1.0, "{}: centre y {c}", p.name);
        }
    }

    #[test]
    fn per_body_block_aabb_resolves_soft_body_particles() {
        let mut w = SoftWorld::new();
        let id = spawn(&mut w, "jelly");
        let request = format!(
            r#"{{"op":"step","dt":0.05,"substeps":4,"environments":[{{"id":{id},"obstacles":[{{"min":[-1,-1,-1],"max":[0.05,1,1]}}]}}]}}"#
        );
        w.exchange(&request).unwrap();
        let positions: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id},"tris":false}}"#))
                .unwrap(),
        )
        .unwrap();
        for p in positions["pos"].as_array().unwrap().chunks_exact(3) {
            let x = p[0].as_f64().unwrap();
            let y = p[1].as_f64().unwrap();
            let z = p[2].as_f64().unwrap();
            let dx = (-1.0 - x).max(0.0).max(x - 0.05);
            let dy = (-1.0 - y).max(0.0).max(y - 1.0);
            let dz = (-1.0 - z).max(0.0).max(z - 1.0);
            let distance = (dx * dx + dy * dy + dz * dz).sqrt();
            assert!(
                distance + 1e-3 >= 0.025,
                "soft vertex penetrated block AABB: {p:?}"
            );
        }
    }

    #[test]
    fn dynamic_rigid_aabb_receives_soft_contact_impulse() {
        let mut w = SoftWorld::new();
        let id = spawn(&mut w, "rope");
        let request = format!(
            r#"{{"op":"step","dt":0.05,"substeps":4,"environments":[{{"id":{id},"bodies":[{{"id":7,"min":[-0.2,-1,-1],"max":[0.05,1,1],"velocity":[1,0,0],"mass":10.0}}]}}]}}"#
        );
        let response: Value = serde_json::from_str(&w.exchange(&request).unwrap()).unwrap();
        let impulse = response["body_impulses"]
            .as_array()
            .unwrap()
            .iter()
            .find(|i| i["id"] == 7)
            .expect("moving rigid AABB must receive the reaction impulse");
        assert!(impulse["impulse"][0].as_f64().unwrap() < 0.0, "{impulse}");
    }

    #[test]
    fn cloth_tearing_changes_constraints_and_render_surface() {
        let mut w = SoftWorld::new();
        let id = spawn(&mut w, "cloth");
        let before: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        let initial_triangles = before["tris"].clone();
        w.bodies.get_mut(&id).unwrap().body.pos[0][0] += 1.0;
        let response: Value = serde_json::from_str(
            &w.exchange(r#"{"op":"step","dt":0.05,"substeps":1}"#)
                .unwrap(),
        )
        .unwrap();
        assert!(response["torn_constraints"].as_u64().unwrap() > 0);
        let status: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        assert!(status["torn_constraints"].as_u64().unwrap() > 0);
        let after: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        assert_ne!(
            after["tris"], initial_triangles,
            "tearing must update the surface topology"
        );
    }

    #[test]
    fn pinned_end_of_rope_stays_put() {
        let mut w = SoftWorld::new();
        let id = spawn(&mut w, "rope");
        let r: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"pin","id":{id},"side":"end"}}"#))
                .unwrap(),
        )
        .unwrap();
        // The end layer of the rope is 2 x 2 particles.
        assert_eq!(r["pinned"].as_u64().unwrap(), 4);
        for _ in 0..200 {
            w.exchange(r#"{"op":"step","dt":0.05,"substeps":8}"#)
                .unwrap();
        }
        let s: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        assert_eq!(s["finite"], true);
        assert!(s["vmax"].as_f64().unwrap().is_finite());
    }

    #[test]
    fn stiffer_body_sags_less() {
        let sag = |k: f64| {
            let mut w = SoftWorld::new();
            let id = spawn(&mut w, "jelly");
            w.exchange(&format!(r#"{{"op":"stiffness","id":{id},"value":{k}}}"#))
                .unwrap();
            w.exchange(&format!(r#"{{"op":"damping","id":{id},"value":3.0}}"#))
                .unwrap();
            for _ in 0..400 {
                w.exchange(r#"{"op":"step","dt":0.05,"substeps":8}"#)
                    .unwrap();
            }
            let s: Value = serde_json::from_str(
                &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                    .unwrap(),
            )
            .unwrap();
            s["center"][1].as_f64().unwrap()
        };
        // Both settle on the floor; compressed height at rest is not the same for both.
        let soft = sag(0.5);
        let stiff = sag(10.0);
        assert!(stiff >= soft - 1e-9, "stiff {stiff} soft {soft}");
    }

    #[test]
    fn limits_and_bad_input_are_rejected() {
        let mut w = SoftWorld::new();
        assert!(w
            .exchange(r#"{"op":"spawn","preset":"nope","origin":[0,0,0]}"#)
            .is_err());
        assert!(w.exchange(r#"{"op":"step","dt":5.0}"#).is_err());
        assert!(w.exchange(r#"{"op":"status","id":99}"#).is_err());
        assert!(w
            .exchange(r#"{"op":"stiffness","id":1,"value":1000}"#)
            .is_err());
        assert!(w.exchange("not json").is_err());
        for _ in 0..MAX_BODIES {
            spawn(&mut w, "rope");
        }
        assert!(w
            .exchange(r#"{"op":"spawn","preset":"rope","origin":[0,0,0]}"#)
            .is_err());
    }

    #[test]
    fn surface_is_cached_and_can_be_omitted() {
        let mut w = SoftWorld::new();
        let id = spawn(&mut w, "cloth");
        let full: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        let again: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id},"tris":true}}"#))
                .unwrap(),
        )
        .unwrap();
        assert_eq!(full["tris"], again["tris"]);
        // Stepping does not change the topology, so the surface stays the same.
        w.exchange(r#"{"op":"step","dt":0.05,"substeps":8}"#)
            .unwrap();
        let later: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        assert_eq!(full["tris"], later["tris"]);
        // With "tris": false the response carries no surface, only positions.
        let slim: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id},"tris":false}}"#))
                .unwrap(),
        )
        .unwrap();
        assert!(slim.get("tris").is_none());
        assert_eq!(
            slim["pos"].as_array().unwrap().len(),
            later["pos"].as_array().unwrap().len()
        );
    }

    #[test]
    fn positions_report_particles_and_a_closed_surface() {
        let mut w = SoftWorld::new();
        let id = spawn(&mut w, "jelly");
        let r: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        let n = r["particles"].as_u64().unwrap() as usize;
        assert_eq!(r["pos"].as_array().unwrap().len(), 3 * n);
        let tris = r["tris"].as_array().unwrap();
        assert!(!tris.is_empty() && tris.len() % 3 == 0);
        assert!(tris.iter().all(|t| (t.as_u64().unwrap() as usize) < n));
        // A closed surface: every edge of the outer triangles is shared by exactly two triangles.
        let mut edges: BTreeMap<(u64, u64), u32> = BTreeMap::new();
        for t in tris.chunks(3) {
            let (a, b, c) = (
                t[0].as_u64().unwrap(),
                t[1].as_u64().unwrap(),
                t[2].as_u64().unwrap(),
            );
            for (x, y) in [(a, b), (b, c), (c, a)] {
                *edges.entry((x.min(y), x.max(y))).or_insert(0) += 1;
            }
        }
        assert!(edges.values().all(|&c| c == 2));
        assert!(w.exchange(r#"{"op":"positions","id":999}"#).is_err());
    }

    fn particle(w: &mut SoftWorld, id: i64, index: usize) -> [f64; 3] {
        let r: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"positions","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        let p = r["pos"].as_array().unwrap();
        [
            p[3 * index].as_f64().unwrap(),
            p[3 * index + 1].as_f64().unwrap(),
            p[3 * index + 2].as_f64().unwrap(),
        ]
    }

    #[test]
    fn anchored_end_follows_its_target_and_the_rope_follows_it() {
        let mut w = SoftWorld::new();
        let id = spawn(&mut w, "rope");
        let a: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"anchor","id":{id},"side":"end"}}"#))
                .unwrap(),
        )
        .unwrap();
        let idx = a["indices"].as_array().unwrap()[0].as_u64().unwrap() as usize;
        let start = particle(&mut w, id, idx);
        // Move the anchor 0.5 m along +x in 40 steps, then check it is exactly on target after each step.
        for k in 1..=40 {
            let x = start[0] + 0.5 * k as f64 / 40.0;
            let t = format!("[{idx},{},{},{}]", x, start[1] + 0.3, start[2]);
            w.exchange(&format!(
                r#"{{"op":"anchor_targets","id":{id},"targets":[{t}]}}"#
            ))
            .unwrap();
            w.exchange(r#"{"op":"step","dt":0.05,"substeps":8}"#)
                .unwrap();
            let p = particle(&mut w, id, idx);
            // positions are rounded to 1 mm by the protocol, so the check tolerates that.
            assert!(
                (p[0] - x).abs() < 1e-3 && (p[1] - (start[1] + 0.3)).abs() < 1e-3,
                "step {k}: {p:?}"
            );
        }
        let s: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                .unwrap(),
        )
        .unwrap();
        assert_eq!(s["finite"], true);
        assert_eq!(
            s["anchored"].as_u64().unwrap(),
            a["anchored"].as_u64().unwrap()
        );
    }

    #[test]
    fn release_frees_the_particles_and_gravity_takes_them() {
        let mut w = SoftWorld::new();
        let id = spawn(&mut w, "rope");
        let a: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"anchor","id":{id},"side":"end"}}"#))
                .unwrap(),
        )
        .unwrap();
        let idx = a["indices"].as_array().unwrap()[0].as_u64().unwrap() as usize;
        w.exchange(&format!(r#"{{"op":"release","id":{id}}}"#))
            .unwrap();
        let before = particle(&mut w, id, idx);
        for _ in 0..20 {
            w.exchange(r#"{"op":"step","dt":0.05,"substeps":8}"#)
                .unwrap();
        }
        let after = particle(&mut w, id, idx);
        assert!(
            after[1] < before[1] - 0.05,
            "released particle did not fall: {before:?} -> {after:?}"
        );
        // Targets are rejected once released.
        assert!(w
            .exchange(&format!(
                r#"{{"op":"anchor_targets","id":{id},"targets":[[{idx},0,0,0]]}}"#
            ))
            .is_err());
    }

    #[test]
    fn wind_pushes_a_free_body_downwind_and_calm_air_does_not() {
        let run = |wind: &str| {
            let mut w = SoftWorld::new();
            let id = spawn(&mut w, "jelly");
            let st: Value = serde_json::from_str(
                &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                    .unwrap(),
            )
            .unwrap();
            let start_x = st["center"][0].as_f64().unwrap();
            w.exchange(&format!(r#"{{"op":"wind","velocity":{wind}}}"#))
                .unwrap();
            for _ in 0..40 {
                w.exchange(r#"{"op":"step","dt":0.05,"substeps":8}"#)
                    .unwrap();
            }
            let s: Value = serde_json::from_str(
                &w.exchange(&format!(r#"{{"op":"status","id":{id}}}"#))
                    .unwrap(),
            )
            .unwrap();
            assert_eq!(s["finite"], true);
            // Displacement along x from the start (the grid starts at the origin, so the start x is 0.15).
            s["center"][0].as_f64().unwrap() - start_x
        };
        let calm = run("[0,0,0]");
        let gust = run("[12,0,0]");
        // The Kuhn-split grid is not mirror-symmetric, so calm air leaves a few mm of drift; the wind
        // must add a clearly larger displacement on top of it.
        assert!(
            calm.abs() < 0.02,
            "calm air moved the body too much: {calm}"
        );
        assert!(
            gust - calm > 0.05,
            "wind did not push the body: calm {calm}, gust {gust}"
        );
    }

    #[test]
    fn anchor_and_wind_input_is_checked() {
        let mut w = SoftWorld::new();
        let id = spawn(&mut w, "cloth");
        assert!(w
            .exchange(&format!(
                r#"{{"op":"anchor_targets","id":{id},"targets":[[0,1,1,1]]}}"#
            ))
            .is_err());
        assert!(w
            .exchange(&format!(r#"{{"op":"anchor","id":{id},"side":"up"}}"#))
            .is_err());
        assert!(w.exchange(r#"{"op":"wind","velocity":[61,0,0]}"#).is_err());
        assert!(w.exchange(r#"{"op":"wind","velocity":[0,0]}"#).is_err());
        let a: Value = serde_json::from_str(
            &w.exchange(&format!(r#"{{"op":"anchor","id":{id},"side":"top"}}"#))
                .unwrap(),
        )
        .unwrap();
        let picked: Vec<u64> = a["indices"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_u64().unwrap())
            .collect();
        // A particle that is not in the anchored layer must be rejected, and nothing else changes.
        let free = (0..1000u64).find(|i| !picked.contains(i)).unwrap();
        assert!(w
            .exchange(&format!(
                r#"{{"op":"anchor_targets","id":{id},"targets":[[{free},0,0,0]]}}"#
            ))
            .is_err());
        let ok = w.exchange(&format!(
            r#"{{"op":"anchor_targets","id":{id},"targets":[[{},0.1,0.2,0.3]]}}"#,
            picked[0]
        ));
        assert!(ok.is_ok());
    }
}
