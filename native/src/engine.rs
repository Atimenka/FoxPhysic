use crate::{
    forces::{self, array, v, FluidField, Surface, Vec3},
    mechanics,
    protocol::*,
    water::Tank,
};
use rapier3d::{
    na::{Isometry3, Point3, Quaternion, Translation3, UnitQuaternion, UnitVector3},
    prelude::*,
};
use std::{collections::BTreeMap, collections::BTreeSet, num::NonZeroUsize, time::Instant};

/// Limits for the per-tick actor and grab sets.
pub const MAX_ACTORS: usize = 64;
pub const MAX_GRABS: usize = 4;

mod assembler;
mod contacts;

#[derive(Clone)]
struct Entry {
    handle: RigidBodyHandle,
    spec: Body,
    surfaces: Vec<Surface>,
    buoyancy: Vec3,
    air: Vec3,
}
#[derive(Clone)]
struct JointEntry {
    handle: ImpulseJointHandle,
    spec: Joint,
}
pub struct World {
    config: Config,
    pipeline: PhysicsPipeline,
    islands: IslandManager,
    broad: BroadPhaseMultiSap,
    narrow: NarrowPhase,
    bodies: RigidBodySet,
    colliders: ColliderSet,
    impulse_joints: ImpulseJointSet,
    multibody_joints: MultibodyJointSet,
    ccd: CCDSolver,
    integration: IntegrationParameters,
    entries: BTreeMap<u64, Entry>,
    springs: BTreeMap<u64, Spring>,
    joints: BTreeMap<u64, JointEntry>,
    /// Fixed body at the origin used by joints anchored to the world.
    world_anchor: Option<RigidBodyHandle>,
    broken: Vec<u64>,
    terrain: BTreeMap<u64, ColliderHandle>,
    tank_colliders: Vec<ColliderHandle>,
    water_cells: BTreeMap<[i32; 3], WaterCell>,
    tank: Option<Tank>,
    wind: Vec3,
    paused: bool,
    accumulator: f64,
    fixed_steps: u64,
    last_step_us: u64,
    /// Actor boxes (players, living entities) from the last request that replaced them.
    actors: Vec<ActorBox>,
    grabs: Vec<Grab>,
    contacts: Vec<ActorContact>,
    contact_step: u64,
}
impl Clone for World {
    /// Deep copy used for rollback. The pipeline keeps no state between steps,
    /// so a fresh one is equivalent.
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            pipeline: PhysicsPipeline::new(),
            islands: self.islands.clone(),
            broad: self.broad.clone(),
            narrow: self.narrow.clone(),
            bodies: self.bodies.clone(),
            colliders: self.colliders.clone(),
            impulse_joints: self.impulse_joints.clone(),
            multibody_joints: self.multibody_joints.clone(),
            ccd: self.ccd.clone(),
            integration: self.integration,
            entries: self.entries.clone(),
            springs: self.springs.clone(),
            joints: self.joints.clone(),
            world_anchor: self.world_anchor,
            broken: self.broken.clone(),
            terrain: self.terrain.clone(),
            tank_colliders: self.tank_colliders.clone(),
            water_cells: self.water_cells.clone(),
            tank: self.tank.clone(),
            wind: self.wind,
            paused: self.paused,
            accumulator: self.accumulator,
            fixed_steps: self.fixed_steps,
            last_step_us: self.last_step_us,
            actors: self.actors.clone(),
            grabs: self.grabs.clone(),
            contacts: self.contacts.clone(),
            contact_step: self.contact_step,
        }
    }
}
impl World {
    pub fn new(config: Config) -> Result<Self, String> {
        config.validate()?;
        let integration = IntegrationParameters {
            dt: 1.0 / config.step_hz as f32,
            num_solver_iterations: NonZeroUsize::new(config.solver_iterations)
                .ok_or("solver_iterations must be >= 1")?,
            max_ccd_substeps: config.ccd_substeps,
            ..IntegrationParameters::default()
        };
        Ok(Self {
            config,
            pipeline: PhysicsPipeline::new(),
            islands: IslandManager::new(),
            broad: BroadPhaseMultiSap::new(),
            narrow: NarrowPhase::new(),
            bodies: RigidBodySet::new(),
            colliders: ColliderSet::new(),
            impulse_joints: ImpulseJointSet::new(),
            multibody_joints: MultibodyJointSet::new(),
            ccd: CCDSolver::new(),
            integration,
            entries: BTreeMap::new(),
            springs: BTreeMap::new(),
            joints: BTreeMap::new(),
            world_anchor: None,
            broken: Vec::new(),
            terrain: BTreeMap::new(),
            tank_colliders: Vec::new(),
            water_cells: BTreeMap::new(),
            tank: None,
            wind: Vec3::zeros(),
            paused: false,
            accumulator: 0.0,
            fixed_steps: 0,
            last_step_us: 0,
            actors: Vec::new(),
            grabs: Vec::new(),
            contacts: Vec::new(),
            contact_step: 0,
        })
    }
    pub fn exchange(&mut self, request: Request) -> Result<Snapshot, String> {
        bounded(request.dt, 0.0, 0.1, "dt")?;
        if request.commands.len() > 64 {
            return Err("at most 64 commands per exchange".into());
        }
        // Validate BOTH environment sections before mutating either one.
        if let Some(boxes) = &request.terrain {
            if boxes.len() > 16384 {
                return Err("terrain collider limit: 16384".into());
            }
            for b in boxes {
                b.validate()?;
            }
        }
        if let Some(cells) = &request.water_cells {
            if cells.len() > 16384 {
                return Err("water cell limit: 16384".into());
            }
            for c in cells {
                if c.cell.iter().any(|x| x.unsigned_abs() > 256) {
                    return Err("water cell out of bounds".into());
                }
                bounded(c.height, 0.001, 1.0, "water.height")?;
                vector(c.velocity, 100.0, "water.velocity")?;
            }
        }
        if let Some(actors) = &request.actors {
            if actors.len() > MAX_ACTORS {
                return Err(format!("at most {MAX_ACTORS} actors"));
            }
            let mut keys = BTreeSet::new();
            for a in actors {
                a.validate()?;
                if !keys.insert(a.key) {
                    return Err(format!("duplicate actor key {}", a.key));
                }
            }
        }
        if let Some(grabs) = &request.grabs {
            if grabs.len() > MAX_GRABS {
                return Err(format!("at most {MAX_GRABS} grabs"));
            }
            for g in grabs {
                g.validate()?;
            }
        }
        let started = Instant::now();
        if let Some(actors) = request.actors {
            self.actors = actors;
        }
        if let Some(grabs) = request.grabs {
            self.grabs = grabs;
        }
        if let Some(boxes) = request.terrain {
            self.replace_terrain(boxes);
        }
        if let Some(cells) = request.water_cells {
            self.water_cells = cells.into_iter().map(|c| (c.cell, c)).collect();
        }
        let mut results = Vec::new();
        for command in request.commands {
            type Outcome = (String, Option<Vec<[i32; 3]>>, Option<SavedState>);
            let result: Result<Outcome, String> = match command {
                Command::DisassembleBody { id, snap } => self
                    .disassemble_body(id, snap)
                    .map(|(m, cells)| (m, Some(cells), None)),
                Command::ExportState => self.export_state().map(|state| {
                    let message = format!(
                        "exported {} bodies, {} joints",
                        state.bodies.len(),
                        state.joints.len()
                    );
                    (message, None, Some(state))
                }),
                other => self.command(other).map(|m| (m, None, None)),
            };
            results.push(match result {
                Ok((message, cells, state)) => CommandResult {
                    ok: true,
                    message,
                    cells,
                    state,
                },
                Err(message) => CommandResult {
                    ok: false,
                    message,
                    cells: None,
                    state: None,
                },
            });
        }
        if !self.paused {
            self.accumulator += request.dt as f64;
            let fixed_dt = 1.0 / self.config.step_hz as f64;
            let mut steps = 0;
            while self.accumulator + 1e-10 >= fixed_dt {
                steps += 1;
                if steps > 25 {
                    return Err("fixed-step budget exceeded".into());
                }
                if let Err(e) = self.fixed_step() {
                    self.paused = true;
                    self.accumulator = 0.0;
                    return Err(e);
                }
                self.accumulator = (self.accumulator - fixed_dt).max(0.0);
            }
        }
        self.last_step_us = started.elapsed().as_micros().min(u64::MAX as u128) as u64;
        let snapshot = self.snapshot(results);
        self.broken.clear();
        Ok(snapshot)
    }
    /// Full replacement (keys are 0..n). Used by the `terrain` request field.
    fn replace_terrain(&mut self, boxes: Vec<TerrainBox>) {
        for (_, h) in std::mem::take(&mut self.terrain) {
            self.colliders.remove(h, &mut self.islands, &mut self.bodies, true);
        }
        for (key, b) in boxes.iter().enumerate() {
            let h = self.insert_terrain(b);
            self.terrain.insert(key as u64, h);
        }
    }
    fn insert_terrain(&mut self, b: &TerrainBox) -> ColliderHandle {
        let c = ColliderBuilder::cuboid(b.size[0] * 0.5, b.size[1] * 0.5, b.size[2] * 0.5)
            .translation(v(b.center))
            .friction(0.8)
            .restitution(0.0)
            .build();
        self.colliders.insert(c)
    }
    /// Upserts keyed boxes and removes keys. Checked as a whole before any change, so a
    /// rejected patch is a no-op. Removing an unknown key is ignored.
    fn patch_terrain(&mut self, add: Vec<KeyedTerrainBox>, remove: Vec<u64>) -> Result<String, String> {
        if add.len() > 16384 {
            return Err("terrain patch: at most 16384 boxes per command".into());
        }
        let mut add_keys = BTreeSet::new();
        for b in &add {
            if !add_keys.insert(b.key) {
                return Err(format!("terrain patch: duplicate key {}", b.key));
            }
            b.as_box().validate()?;
        }
        let remove: BTreeSet<u64> = remove.into_iter().collect();
        let present_removed: Vec<u64> = remove
            .iter()
            .copied()
            .filter(|k| self.terrain.contains_key(k))
            .collect();
        let net_removed = present_removed.iter().filter(|k| !add_keys.contains(k)).count();
        let net_added = add_keys.iter().filter(|k| !self.terrain.contains_key(k)).count();
        if self.terrain.len() - net_removed + net_added > 16384 {
            return Err("terrain collider limit: 16384".into());
        }
        for k in &remove {
            if let Some(h) = self.terrain.remove(k) {
                self.colliders.remove(h, &mut self.islands, &mut self.bodies, true);
            }
        }
        for b in &add {
            if let Some(old) = self.terrain.remove(&b.key) {
                self.colliders.remove(old, &mut self.islands, &mut self.bodies, true);
            }
            let h = self.insert_terrain(&b.as_box());
            self.terrain.insert(b.key, h);
        }
        Ok(format!(
            "terrain: {} upserted, {} removed, {} colliders",
            add.len(),
            present_removed.len(),
            self.terrain.len()
        ))
    }
    fn command(&mut self, command: Command) -> Result<String, String> {
        match command {
            Command::AddBody { body } => {
                let id = body.id;
                self.add_body(body)?;
                Ok(format!("body {id} created"))
            }
            Command::RemoveBody { id } => {
                self.remove_body(id)?;
                Ok(format!("body {id} removed"))
            }
            Command::SetPose { id, position, rotation } => {
                vector(position, 256.0, "set_pose.position")?;
                let q = rotation;
                let norm2: f32 = q.iter().map(|x| x * x).sum();
                if !norm2.is_finite() || norm2 < 1e-6 || q.iter().any(|x| !x.is_finite()) {
                    return Err("set_pose.rotation must be a finite, non-zero quaternion".into());
                }
                let h = self
                    .entries
                    .get(&id)
                    .ok_or_else(|| format!("unknown body {id}"))?
                    .handle;
                let rot = UnitQuaternion::new_normalize(Quaternion::new(q[3], q[0], q[1], q[2]));
                let iso = Isometry3::from_parts(Translation3::from(v(position)), rot);
                let rb = self.bodies.get_mut(h).ok_or("missing rigid body")?;
                rb.set_position(iso, true);
                rb.set_linvel(Vector::zeros(), true);
                rb.set_angvel(Vector::zeros(), true);
                Ok(format!("pose of body {id} set"))
            }
            Command::Impulse { id, impulse, point } => {
                vector(impulse, 2_000_000.0, "impulse")?;
                if let Some(p) = point {
                    vector(p, 256.0, "impulse.point")?;
                }
                let h = self
                    .entries
                    .get(&id)
                    .ok_or_else(|| format!("unknown body {id}"))?
                    .handle;
                let rb = self.bodies.get_mut(h).ok_or("missing rigid body")?;
                if let Some(p) = point {
                    rb.apply_impulse_at_point(v(impulse), Point3::from(v(p)), true);
                } else {
                    rb.apply_impulse(v(impulse), true);
                }
                Ok(format!("impulse applied to {id}"))
            }
            Command::SetWind { velocity } => {
                vector(velocity, 100.0, "wind")?;
                self.wind = v(velocity);
                Ok("wind changed (m/s)".into())
            }
            Command::SetTank { tank } => {
                self.set_tank(tank)?;
                Ok("shallow-water tank created".into())
            }
            Command::RemoveTank => {
                self.remove_tank();
                Ok("tank removed".into())
            }
            Command::AddSpring { spring } => {
                self.add_spring(spring)?;
                Ok("spring created".into())
            }
            Command::RemoveSpring { id } => {
                if self.springs.remove(&id).is_none() {
                    return Err(format!("unknown spring {id}"));
                }
                Ok(format!("spring {id} removed"))
            }
            Command::AddJoint { joint } => {
                let id = joint.id;
                self.add_joint(joint)?;
                Ok(format!("joint {id} created"))
            }
            Command::RemoveJoint { id } => {
                self.remove_joint(id)?;
                Ok(format!("joint {id} removed"))
            }
            Command::ExportState => Ok("state exported".into()),
            Command::ImportState { state } => self.import_state(state),
            Command::AssembleBody { id, cells } => self.assemble_body(id, cells),
            Command::AttachCells { id, cells } => self.attach_cells(id, cells),
            Command::DisassembleBody { id, snap } => {
                self.disassemble_body(id, snap).map(|(m, _)| m)
            }
            Command::PatchTerrain { add, remove } => self.patch_terrain(add, remove),
            Command::Pause { paused } => {
                self.paused = paused;
                self.accumulator = 0.0;
                Ok(format!("paused={paused}"))
            }
        }
    }
    fn add_body(&mut self, spec: Body) -> Result<(), String> {
        self.add_body_with(spec, zero())
    }
    fn add_body_with(&mut self, spec: Body, angular_velocity: V3) -> Result<(), String> {
        spec.validate()?;
        vector(angular_velocity, 1000.0, "body.angular_velocity")?;
        if self.entries.contains_key(&spec.id) {
            return Err("duplicate body id".into());
        }
        if self.config.max_bodies != 0 && self.entries.len() >= self.config.max_bodies {
            return Err("body count limit reached".into());
        }
        if self
            .entries
            .values()
            .map(|e| e.spec.parts.len())
            .sum::<usize>()
            + spec.parts.len()
            > 256
        {
            return Err("total visual/material part limit: 256".into());
        }
        let q = spec.rotation;
        let rotation = UnitQuaternion::new_normalize(Quaternion::new(q[3], q[0], q[1], q[2]));
        let iso = Isometry3::from_parts(Translation3::from(v(spec.position)), rotation);
        let handle = self.bodies.insert(
            RigidBodyBuilder::dynamic()
                .position(iso)
                .linvel(v(spec.velocity))
                .angvel(v(angular_velocity))
                .ccd_enabled(true)
                .angular_damping(0.01)
                .linear_damping(0.0)
                .build(),
        );
        for part in &spec.parts {
            let c =
                ColliderBuilder::cuboid(part.size[0] * 0.5, part.size[1] * 0.5, part.size[2] * 0.5)
                    .translation(v(part.offset))
                    .density(part.density)
                    .friction(part.friction)
                    .restitution(part.restitution)
                    .build();
            self.colliders
                .insert_with_parent(c, handle, &mut self.bodies);
        }
        self.bodies[handle].recompute_mass_properties_from_colliders(&self.colliders);
        let surfaces = forces::exposed_surfaces(&spec.parts);
        self.entries.insert(
            spec.id,
            Entry {
                handle,
                spec,
                surfaces,
                buoyancy: Vec3::zeros(),
                air: Vec3::zeros(),
            },
        );
        Ok(())
    }
    fn remove_body(&mut self, id: u64) -> Result<(), String> {
        let entry = self
            .entries
            .remove(&id)
            .ok_or_else(|| format!("unknown body {id}"))?;
        self.bodies.remove(
            entry.handle,
            &mut self.islands,
            &mut self.colliders,
            &mut self.impulse_joints,
            &mut self.multibody_joints,
            true,
        );
        self.springs.retain(|_, s| s.b != id && s.a != Some(id));
        self.joints
            .retain(|_, j| j.spec.b != id && j.spec.a != Some(id));
        Ok(())
    }
    fn anchor_body(&mut self) -> RigidBodyHandle {
        if let Some(h) = self.world_anchor {
            return h;
        }
        let h = self.bodies.insert(RigidBodyBuilder::fixed().build());
        self.world_anchor = Some(h);
        h
    }
    fn add_joint(&mut self, j: Joint) -> Result<(), String> {
        j.validate()?;
        if j.id == 0 || self.joints.contains_key(&j.id) || self.joints.len() >= 256 {
            return Err("invalid/duplicate joint id or joint limit (256)".into());
        }
        if j.a == Some(j.b) {
            return Err("joint must connect different bodies".into());
        }
        let hb = self
            .entries
            .get(&j.b)
            .ok_or("unknown joint body b")?
            .handle;
        let ha = match j.a {
            Some(id) => self.entries.get(&id).ok_or("unknown joint body a")?.handle,
            None => self.anchor_body(),
        };
        let axis = UnitVector3::new_normalize(v(j.axis));
        let a1 = Point3::from(v(j.anchor_a));
        let a2 = Point3::from(v(j.anchor_b));
        let data: GenericJoint = match j.kind {
            JointKind::Hinge => {
                let mut b = RevoluteJointBuilder::new(axis)
                    .local_anchor1(a1)
                    .local_anchor2(a2);
                if let Some(l) = j.limits {
                    b = b.limits(l);
                }
                b.build().into()
            }
            JointKind::Slider => {
                let mut b = PrismaticJointBuilder::new(axis)
                    .local_anchor1(a1)
                    .local_anchor2(a2);
                if let Some(l) = j.limits {
                    b = b.limits(l);
                }
                b.build().into()
            }
            JointKind::Rope => RopeJointBuilder::new(j.max_length)
                .local_anchor1(a1)
                .local_anchor2(a2)
                .build()
                .into(),
            JointKind::ConeTwist => {
                let half = j.limits.map(|l| l[1]).unwrap_or(0.5);
                GenericJointBuilder::new(
                    JointAxesMask::LIN_X | JointAxesMask::LIN_Y | JointAxesMask::LIN_Z,
                )
                .local_anchor1(a1)
                .local_anchor2(a2)
                .limits(JointAxis::AngY, [-half, half])
                .limits(JointAxis::AngZ, [-half, half])
                .build()
            }
        };
        let handle = self.impulse_joints.insert(ha, hb, data, true);
        self.joints.insert(j.id, JointEntry { handle, spec: j });
        Ok(())
    }
    fn remove_joint(&mut self, id: u64) -> Result<(), String> {
        let entry = self
            .joints
            .remove(&id)
            .ok_or_else(|| format!("unknown joint {id}"))?;
        self.impulse_joints.remove(entry.handle, true);
        Ok(())
    }
    fn export_state(&self) -> Result<SavedState, String> {
        let bodies = self
            .entries
            .values()
            .map(|e| {
                let rb = &self.bodies[e.handle];
                let q = rb.rotation().quaternion();
                let mut body = e.spec.clone();
                body.position = array(*rb.translation());
                body.rotation = [q.i, q.j, q.k, q.w];
                body.velocity = array(*rb.linvel());
                SavedBody {
                    body,
                    angular_velocity: array(*rb.angvel()),
                }
            })
            .collect();
        Ok(SavedState {
            format: SAVE_FORMAT,
            bodies,
            springs: self.springs.values().cloned().collect(),
            joints: self.joints.values().map(|j| j.spec.clone()).collect(),
            wind: array(self.wind),
        })
    }
    /// Replaces bodies, springs and joints. Environment (terrain, water, tank) is kept.
    /// On any failure the world is restored to its state before the call.
    fn import_state(&mut self, state: SavedState) -> Result<String, String> {
        if state.format != SAVE_FORMAT {
            return Err(format!("unsupported save format {}", state.format));
        }
        if self.config.max_bodies != 0 && state.bodies.len() > self.config.max_bodies {
            return Err("saved body count exceeds max_bodies".into());
        }
        vector(state.wind, 100.0, "wind")?;
        let mut ids = BTreeSet::new();
        for sb in &state.bodies {
            sb.body.validate()?;
            if !ids.insert(sb.body.id) {
                return Err(format!("duplicate saved body {}", sb.body.id));
            }
        }
        for s in &state.springs {
            if !ids.contains(&s.b) || s.a.is_some_and(|a| !ids.contains(&a)) {
                return Err(format!("saved spring {} references a missing body", s.id));
            }
        }
        for j in &state.joints {
            j.validate()?;
            if !ids.contains(&j.b) || j.a.is_some_and(|a| !ids.contains(&a)) {
                return Err(format!("saved joint {} references a missing body", j.id));
            }
        }
        let backup = self.clone();
        match self.apply_state(state) {
            Ok(message) => Ok(message),
            Err(e) => {
                *self = backup;
                Err(format!("import rolled back: {e}"))
            }
        }
    }
    fn apply_state(&mut self, state: SavedState) -> Result<String, String> {
        let joint_ids: Vec<u64> = self.joints.keys().copied().collect();
        for id in joint_ids {
            self.remove_joint(id)?;
        }
        let body_ids: Vec<u64> = self.entries.keys().copied().collect();
        for id in body_ids {
            self.remove_body(id)?;
        }
        self.springs.clear();
        self.wind = v(state.wind);
        let (bodies, springs, joints) = (
            state.bodies.len(),
            state.springs.len(),
            state.joints.len(),
        );
        for sb in state.bodies {
            self.add_body_with(sb.body, sb.angular_velocity)?;
        }
        for s in state.springs {
            self.add_spring(s)?;
        }
        for j in state.joints {
            self.add_joint(j)?;
        }
        Ok(format!(
            "imported {bodies} bodies, {springs} springs, {joints} joints"
        ))
    }
    fn remove_tank(&mut self) {
        self.tank = None;
        for h in self.tank_colliders.drain(..) {
            self.colliders
                .remove(h, &mut self.islands, &mut self.bodies, true);
        }
    }
    fn set_tank(&mut self, spec: TankSpec) -> Result<(), String> {
        let tank = Tank::new(spec.clone(), self.config.gravity)?; // validate before replacing
        let boxes = tank_geometry(&spec);
        self.remove_tank();
        for b in boxes {
            let c = ColliderBuilder::cuboid(b.size[0] * 0.5, b.size[1] * 0.5, b.size[2] * 0.5)
                .translation(v(b.center))
                .friction(0.6)
                .build();
            self.tank_colliders.push(self.colliders.insert(c));
        }
        self.tank = Some(tank);
        Ok(())
    }
    fn add_spring(&mut self, s: Spring) -> Result<(), String> {
        if s.id == 0 || self.springs.contains_key(&s.id) || self.springs.len() >= 128 {
            return Err("invalid/duplicate spring id or spring limit".into());
        }
        if s.a == Some(s.b) {
            return Err("spring must connect different bodies".into());
        }
        vector(s.anchor_a, 128.0, "spring.anchor_a")?;
        vector(s.anchor_b, 32.0, "spring.anchor_b")?;
        bounded(s.rest_length, 0.01, 64.0, "spring.rest_length")?;
        bounded(s.stiffness, 0.0, 1_000_000.0, "spring.stiffness")?;
        bounded(s.damping, 0.0, 1_000_000.0, "spring.damping")?;
        let b = self.entries.get(&s.b).ok_or("unknown spring body b")?;
        let mb = self.bodies[b.handle].mass();
        let effective_mass = if let Some(id) = s.a {
            let a = self.entries.get(&id).ok_or("unknown spring body a")?;
            let ma = self.bodies[a.handle].mass();
            ma * mb / (ma + mb)
        } else {
            mb
        };
        let dt = self.integration.dt;
        if s.stiffness * dt * dt / effective_mass > 0.25 || s.damping * dt / effective_mass > 0.5 {
            return Err(
                "spring too stiff/damped for this mass and timestep; reduce coefficients".into(),
            );
        }
        self.springs.insert(s.id, s);
        Ok(())
    }
    fn fixed_step(&mut self) -> Result<(), String> {
        let dt = self.integration.dt;
        if let Some(t) = self.tank.as_mut() {
            t.advance(1.0 / self.config.step_hz as f64)?;
        }
        let fluid = FluidField {
            tank: self.tank.as_ref(),
            cells: &self.water_cells,
        };
        for e in self.entries.values_mut() {
            let rb = &mut self.bodies[e.handle];
            rb.reset_forces(false);
            rb.reset_torques(false);
            let bundle = forces::body_forces(
                rb,
                &e.spec,
                &e.surfaces,
                &fluid,
                &self.config,
                self.wind,
                dt,
            );
            e.buoyancy = bundle.buoyancy;
            e.air = bundle.air;
            for (f, p) in bundle.forces {
                if !f.iter().all(|x| x.is_finite()) {
                    return Err("non-finite environmental force".into());
                }
                if f.norm_squared() > 1e-10 {
                    rb.add_force_at_point(f, p, true);
                }
            }
        }
        for s in self.springs.values() {
            let hb = self.entries[&s.b].handle;
            let rb = &self.bodies[hb];
            let pb = rb.position() * Point3::from(v(s.anchor_b));
            let vb = rb.velocity_at_point(&pb);
            let (ha, pa, va) = if let Some(id) = s.a {
                let h = self.entries[&id].handle;
                let a = &self.bodies[h];
                let p = a.position() * Point3::from(v(s.anchor_a));
                (Some(h), p, a.velocity_at_point(&p))
            } else {
                (None, Point3::from(v(s.anchor_a)), Vec3::zeros())
            };
            let f = mechanics::force_on_b(
                pa.coords,
                va,
                pb.coords,
                vb,
                s.rest_length,
                s.stiffness,
                s.damping,
            );
            self.bodies[hb].add_force_at_point(f, pb, true);
            if let Some(h) = ha {
                self.bodies[h].add_force_at_point(-f, pa, true);
            }
        }
        self.apply_grabs();
        let gravity = Vec3::new(0.0, -self.config.gravity, 0.0);
        self.pipeline.step(
            &gravity,
            &self.integration,
            &mut self.islands,
            &mut self.broad,
            &mut self.narrow,
            &mut self.bodies,
            &mut self.colliders,
            &mut self.impulse_joints,
            &mut self.multibody_joints,
            &mut self.ccd,
            None,
            &(),
            &(),
        );
        self.fixed_steps += 1;
        self.compute_contacts();
        let mut broke = Vec::new();
        for (&id, e) in &self.joints {
            if let Some(limit) = e.spec.break_force {
                if let Some(joint) = self.impulse_joints.get(e.handle) {
                    let force = joint.impulses.fixed_rows::<3>(0).norm() / dt;
                    if !force.is_finite() || force > limit {
                        broke.push(id);
                    }
                }
            }
        }
        for id in broke {
            self.remove_joint(id)?;
            self.broken.push(id);
        }
        let mut expired = Vec::new();
        for (&id, e) in &self.entries {
            let rb = &self.bodies[e.handle];
            if !rb
                .translation()
                .iter()
                .chain(rb.linvel().iter())
                .chain(rb.angvel().iter())
                .all(|x| x.is_finite())
            {
                return Err(format!("body {id} became non-finite; simulation paused"));
            }
            if rb.translation().iter().any(|x| x.abs() > 128.0) {
                expired.push(id);
            }
        }
        for id in expired {
            self.remove_body(id)?;
        }
        Ok(())
    }
    pub fn snapshot(&self, results: Vec<CommandResult>) -> Snapshot {
        let bodies = self
            .entries
            .iter()
            .map(|(&id, e)| {
                let rb = &self.bodies[e.handle];
                let q = rb.rotation().quaternion();
                let (aabb_min, aabb_max) = world_bounds(rb, &e.spec.parts);
                BodyState {
                    id,
                    position: array(*rb.translation()),
                    rotation: [q.i, q.j, q.k, q.w],
                    velocity: array(*rb.linvel()),
                    angular_velocity: array(*rb.angvel()),
                    mass: rb.mass(),
                    parts: e.spec.parts.clone(),
                    buoyancy_force: array(e.buoyancy),
                    air_force: array(e.air),
                    aabb_min,
                    aabb_max,
                }
            })
            .collect();
        Snapshot {
            abi: ABI_VERSION,
            sim_seconds: self.fixed_steps as f64 / self.config.step_hz as f64,
            fixed_steps: self.fixed_steps,
            paused: self.paused,
            bodies,
            tank: self.tank.as_ref().map(Tank::state),
            wind: array(self.wind),
            last_step_us: self.last_step_us,
            static_colliders: self.terrain.len() + self.tank_colliders.len(),
            results,
            joint_count: self.joints.len(),
            broken_joints: self.broken.clone(),
            contacts: self.contacts.clone(),
            contact_step: self.contact_step,
        }
    }
}

/// Exact world AABB of the oriented boxes: each half-size is |R| * half-extent.
fn world_bounds(rb: &RigidBody, parts: &[Part]) -> (V3, V3) {
    let origin = *rb.translation();
    let r = *rb.rotation().to_rotation_matrix().matrix();
    let mut lo = [f32::INFINITY; 3];
    let mut hi = [f32::NEG_INFINITY; 3];
    for p in parts {
        let centre = origin + r * v(p.offset);
        for i in 0..3 {
            let half = (0..3).map(|j| r[(i, j)].abs() * p.size[j] * 0.5).sum::<f32>();
            lo[i] = lo[i].min(centre[i] - half);
            hi[i] = hi[i].max(centre[i] + half);
        }
    }
    if parts.is_empty() {
        return (array(origin), array(origin));
    }
    (lo, hi)
}

pub fn tank_geometry(s: &TankSpec) -> Vec<TerrainBox> {
    let [x, y, z] = s.origin;
    let w = s.nx as f32 * s.dx;
    let d = s.nz as f32 * s.dx;
    let h = 10.0;
    vec![
        TerrainBox {
            center: [x + w * 0.5, y - 0.25, z + d * 0.5],
            size: [w + 0.4, 0.5, d + 0.4],
        },
        TerrainBox {
            center: [x - 0.1, y + h * 0.5, z + d * 0.5],
            size: [0.2, h, d + 0.4],
        },
        TerrainBox {
            center: [x + w + 0.1, y + h * 0.5, z + d * 0.5],
            size: [0.2, h, d + 0.4],
        },
        TerrainBox {
            center: [x + w * 0.5, y + h * 0.5, z - 0.1],
            size: [w, h, 0.2],
        },
        TerrainBox {
            center: [x + w * 0.5, y + h * 0.5, z + d + 0.1],
            size: [w, h, 0.2],
        },
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    fn cube(id: u64, y: f32, density: f32) -> Body {
        Body {
            id,
            position: [5.0, y, 5.0],
            rotation: identity(),
            velocity: zero(),
            parts: vec![Part {
                offset: zero(),
                size: [1.0; 3],
                density,
                friction: 0.6,
                restitution: 0.0,
                block: "minecraft:oak_planks".into(),
            }],
            wing: None,
        }
    }
    fn spawn(w: &mut World, b: Body) {
        let s = w
            .exchange(Request {
                commands: vec![Command::AddBody { body: b }],
                ..Request::default()
            })
            .unwrap();
        assert!(s.results[0].ok, "{}", s.results[0].message);
    }
    fn tick(w: &mut World, n: usize) -> Snapshot {
        let mut s = w.snapshot(vec![]);
        for _ in 0..n {
            s = w
                .exchange(Request {
                    dt: 0.05,
                    ..Request::default()
                })
                .unwrap();
        }
        s
    }
    #[test]
    fn fast_body_does_not_tunnel_with_one_ccd_substep() {
        let config = Config {
            ccd_substeps: 1,
            ..Config::default()
        };
        let mut w = World::new(config).unwrap();
        w.exchange(Request {
            terrain: Some(vec![TerrainBox {
                center: [0.0, 0.0, 0.0],
                size: [20.0, 0.05, 20.0],
            }]),
            ..Request::default()
        })
        .unwrap();
        let mut c = cube(1, 30.0, 2500.0);
        c.velocity = [0.0, -90.0, 0.0];
        spawn(&mut w, c);
        let s = tick(&mut w, 60);
        let y = s.bodies[0].position[1];
        assert!(y > 0.5, "one CCD substep tunnelled: centre y = {y}");
    }
    #[test]
    fn fast_body_does_not_tunnel_through_thin_terrain() {
        // A 5 cm slab at y=0 (top at 0.025 m) and a cube falling at 90 m/s (protocol maximum).
        // One 1/120 s step moves it 1.7 m, far more than the slab thickness.
        let mut w = World::new(Config::default()).unwrap();
        w.exchange(Request {
            terrain: Some(vec![TerrainBox {
                center: [0.0, 0.0, 0.0],
                size: [20.0, 0.05, 20.0],
            }]),
            ..Request::default()
        })
        .unwrap();
        let mut c = cube(1, 30.0, 2500.0);
        c.velocity = [0.0, -90.0, 0.0];
        spawn(&mut w, c);
        let s = tick(&mut w, 60);
        let y = s.bodies[0].position[1];
        assert!(y > 0.5, "body tunnelled through the slab: centre y = {y}");
    }
    #[test]
    fn fall_from_100m_stops_on_5cm_plate() {
        // alpha.7 acceptance: a 1 m cube dropped from 100 m onto a 5 cm plate
        // (top at 0.025 m) must come to rest on the plate, not pass through it.
        let mut w = World::new(Config::default()).unwrap();
        w.exchange(Request {
            terrain: Some(vec![TerrainBox {
                center: [0.0, 0.0, 0.0],
                size: [20.0, 0.05, 20.0],
            }]),
            ..Request::default()
        })
        .unwrap();
        spawn(&mut w, cube(1, 100.0, 2500.0));
        let s = tick(&mut w, 600);
        let y = s.bodies[0].position[1];
        assert!(y > 0.4 && y < 0.7, "cube did not rest on the plate: centre y = {y}");
    }
    #[test]
    fn body_far_from_anchor_rests_on_terrain() {
        // alpha.8 acceptance (stage 8): the Lab anchor is not the body's location. A 1 m cube
        // 100 m from the anchor, on a plate at that distance, must still rest on the plate.
        let mut w = World::new(Config::default()).unwrap();
        w.exchange(Request {
            terrain: Some(vec![TerrainBox { center: [100.0, 0.0, 0.0], size: [20.0, 0.05, 20.0] }]),
            ..Request::default()
        })
        .unwrap();
        let mut far = cube(1, 100.0, 2500.0);
        far.position = [100.0, 100.0, 0.0];
        spawn(&mut w, far);
        let s = tick(&mut w, 600);
        let y = s.bodies[0].position[1];
        assert!(y > 0.4 && y < 0.7, "far cube did not rest on the plate: centre y = {y}");
    }
    #[test]
    fn settled_cube_does_not_drift_sideways() {
        // alpha.7 acceptance: resting on a floor, the body centre must not drift in x/z by more
        // than 5 cm (the reported bug was 15-30 cm during settling).
        let mut w = World::new(Config::default()).unwrap();
        w.exchange(Request {
            terrain: Some(vec![TerrainBox { center: [5.0, -0.5, 5.0], size: [20.0, 1.0, 20.0] }]),
            ..Request::default()
        })
        .unwrap();
        spawn(&mut w, cube(1, 3.0, 2500.0));
        let s = tick(&mut w, 200);
        let p = s.bodies[0].position;
        assert!((p[0] - 5.0).abs() < 0.05 && (p[2] - 5.0).abs() < 0.05, "drift x/z = {:?}", p);
    }
    #[test]
    fn off_centre_mass_settles_without_tipping_or_sliding() {
        // Heavy half and light half: the centre of mass sits 0.36 m off the geometric centre.
        let mut w = World::new(Config::default()).unwrap();
        w.exchange(Request {
            terrain: Some(vec![TerrainBox { center: [5.0, -0.5, 5.0], size: [20.0, 1.0, 20.0] }]),
            ..Request::default()
        })
        .unwrap();
        let mut b = cube(1, 3.0, 2600.0);
        b.parts.push(Part {
            offset: [-0.5, 0.0, 0.0],
            size: [1.0; 3],
            density: 200.0,
            friction: 0.6,
            restitution: 0.0,
            block: "minecraft:oak_planks".into(),
        });
        b.parts[0].offset = [0.5, 0.0, 0.0];
        spawn(&mut w, b);
        let s = tick(&mut w, 200);
        let p = s.bodies[0].position;
        let q = s.bodies[0].rotation;
        // Tilt from upright: 1 - |q.y|-ish for a rotation about the vertical axis is ignored; any
        // tip about x or z shows as a large x/z component of the quaternion.
        let tilt = (q[0] * q[0] + q[2] * q[2]).sqrt();
        assert!((p[0] - 5.0).abs() < 0.05 && (p[2] - 5.0).abs() < 0.05, "slid: {:?}", p);
        assert!(tilt < 0.05, "tipped: rotation {:?}", q);
    }
    #[test]
    fn snapshot_aabb_bounds_a_rotated_box_exactly() {
        let mut w = World::new(Config::default()).unwrap();
        let h = std::f32::consts::FRAC_PI_8; // 45 degrees about Y
        let body = Body {
            id: 3,
            position: [0.0, 10.0, 0.0],
            rotation: [0.0, h.sin(), 0.0, h.cos()],
            velocity: zero(),
            parts: vec![Part {
                offset: zero(),
                size: [1.0; 3],
                density: 500.0,
                friction: 0.6,
                restitution: 0.0,
                block: "minecraft:oak_planks".into(),
            }],
            wing: None,
        };
        let s = w
            .exchange(Request { dt: 0.0, terrain: None, water_cells: None, actors: None, grabs: None, commands: vec![Command::AddBody { body }] })
            .unwrap();
        let b = &s.bodies[0];
        let half_diag = 0.5 * 2.0f32.sqrt();
        assert!((b.aabb_max[0] - half_diag).abs() < 1e-4 && (b.aabb_min[0] + half_diag).abs() < 1e-4);
        assert!((b.aabb_max[1] - 10.5).abs() < 1e-4 && (b.aabb_min[1] - 9.5).abs() < 1e-4);
    }

    #[test]
    fn gravity_is_in_si_units() {
        let mut w = World::new(Config {
            air_density: 0.0,
            ..Config::default()
        })
        .unwrap();
        spawn(&mut w, cube(1, 20.0, 1000.0));
        let s = tick(&mut w, 20);
        assert_eq!(s.fixed_steps, 120);
        assert!((s.bodies[0].velocity[1] + 9.81).abs() < 0.02);
        assert!((s.bodies[0].position[1] - (20.0 - 4.905)).abs() < 0.08);
    }
    #[test]
    fn cube_collides_with_ground() {
        let mut w = World::new(Config::default()).unwrap();
        w.exchange(Request {
            terrain: Some(vec![TerrainBox {
                center: [5.0, -0.5, 5.0],
                size: [20.0, 1.0, 20.0],
            }]),
            ..Request::default()
        })
        .unwrap();
        spawn(&mut w, cube(1, 3.0, 2500.0));
        let s = tick(&mut w, 100);
        assert!(
            (s.bodies[0].position[1] - 0.5).abs() < 0.03,
            "{}",
            s.bodies[0].position[1]
        );
    }
    #[test]
    fn full_submersion_has_archimedes_force() {
        let mut w = World::new(Config::default()).unwrap();
        let tank = TankSpec {
            origin: [0.0; 3],
            nx: 12,
            nz: 12,
            dx: 1.0,
            depth_left: 3.0,
            depth_right: 3.0,
            viscosity: 0.0,
            damping: 0.0,
            roughness: 0.0,
        };
        w.exchange(Request {
            commands: vec![Command::SetTank { tank }],
            ..Request::default()
        })
        .unwrap();
        spawn(&mut w, cube(1, 1.5, 550.0));
        let s = tick(&mut w, 1);
        assert!((s.bodies[0].buoyancy_force[1] - 998.2 * 9.81).abs() < 0.1);
        assert!(s.bodies[0].velocity[1] > 0.0);
    }
    #[test]
    fn wood_floats_and_stone_sinks() {
        let mut w = World::new(Config::default()).unwrap();
        let tank = TankSpec {
            origin: [0.0; 3],
            nx: 12,
            nz: 12,
            dx: 1.0,
            depth_left: 3.0,
            depth_right: 3.0,
            viscosity: 0.0,
            damping: 0.02,
            roughness: 0.0,
        };
        w.exchange(Request {
            commands: vec![Command::SetTank { tank }],
            ..Request::default()
        })
        .unwrap();
        spawn(&mut w, cube(1, 4.0, 550.0));
        let mut stone = cube(2, 4.0, 2600.0);
        stone.position[0] = 8.0;
        spawn(&mut w, stone);
        let s = tick(&mut w, 160);
        let wood = &s.bodies[0];
        let rock = &s.bodies[1];
        assert!(
            (wood.position[1] - (3.0 + 0.5 - 550.0 / 998.2)).abs() < 0.18,
            "wood={}",
            wood.position[1]
        );
        assert!(rock.position[1] < 0.55, "stone={}", rock.position[1]);
    }
    #[test]
    fn command_failure_is_non_destructive() {
        let mut w = World::new(Config::default()).unwrap();
        spawn(&mut w, cube(1, 2.0, 550.0));
        let s = w
            .exchange(Request {
                commands: vec![Command::AddBody {
                    body: cube(1, 2.0, 550.0),
                }],
                ..Request::default()
            })
            .unwrap();
        assert!(!s.results[0].ok);
        assert_eq!(s.bodies.len(), 1);
    }
    #[test]
    fn pause_does_not_accumulate_backlog() {
        let mut w = World::new(Config::default()).unwrap();
        spawn(&mut w, cube(1, 10.0, 550.0));
        w.exchange(Request {
            commands: vec![Command::Pause { paused: true }],
            ..Request::default()
        })
        .unwrap();
        assert_eq!(tick(&mut w, 20).fixed_steps, 0);
        w.exchange(Request {
            commands: vec![Command::Pause { paused: false }],
            ..Request::default()
        })
        .unwrap();
        assert_eq!(tick(&mut w, 1).fixed_steps, 6);
    }
    #[test]
    fn unstable_spring_rejected() {
        let mut w = World::new(Config::default()).unwrap();
        spawn(&mut w, cube(1, 2.0, 1.0));
        let s = w
            .exchange(Request {
                commands: vec![Command::AddSpring {
                    spring: Spring {
                        id: 1,
                        a: None,
                        b: 1,
                        anchor_a: [5.0, 5.0, 5.0],
                        anchor_b: zero(),
                        rest_length: 1.0,
                        stiffness: 1_000_000.0,
                        damping: 0.0,
                    },
                }],
                ..Request::default()
            })
            .unwrap();
        assert!(!s.results[0].ok);
    }
    #[test]
    fn overlapping_parts_rejected() {
        let mut b = cube(1, 2.0, 550.0);
        b.parts.push(b.parts[0].clone());
        assert!(b.validate().is_err());
    }
    #[test]
    fn excessive_elapsed_time_rejected() {
        let mut w = World::new(Config::default()).unwrap();
        assert!(w
            .exchange(Request {
                dt: 2.0,
                ..Request::default()
            })
            .is_err());
    }
    #[test]
    fn extreme_quaternion_rejected_before_normalization() {
        let mut b = cube(1, 2.0, 550.0);
        b.rotation = [1e30; 4];
        assert!(b.validate().is_err());
    }
    #[test]
    fn minimum_integer_water_key_rejected_without_panic() {
        let mut w = World::new(Config::default()).unwrap();
        assert!(w
            .exchange(Request {
                water_cells: Some(vec![WaterCell {
                    cell: [i32::MIN, 0, 0],
                    height: 1.0,
                    velocity: zero()
                }]),
                ..Request::default()
            })
            .is_err());
        assert!(w.exchange(Request::default()).is_ok());
    }
    #[test]
    fn pair_spring_conserves_horizontal_momentum() {
        let mut w = World::new(Config {
            air_density: 0.0,
            ..Config::default()
        })
        .unwrap();
        let mut a = cube(1, 15.0, 1000.0);
        a.position[0] = 4.0;
        let mut b = cube(2, 15.0, 1000.0);
        b.position[0] = 7.0;
        spawn(&mut w, a);
        spawn(&mut w, b);
        let s = w
            .exchange(Request {
                commands: vec![Command::AddSpring {
                    spring: Spring {
                        id: 1,
                        a: Some(1),
                        b: 2,
                        anchor_a: zero(),
                        anchor_b: zero(),
                        rest_length: 2.0,
                        stiffness: 2000.0,
                        damping: 20.0,
                    },
                }],
                ..Request::default()
            })
            .unwrap();
        assert!(s.results[0].ok);
        let s = tick(&mut w, 20);
        let px = s.bodies.iter().map(|b| b.mass * b.velocity[0]).sum::<f32>();
        assert!(px.abs() < 0.02, "horizontal momentum={px}");
    }

    fn joint(id: u64, kind: JointKind, a: Option<u64>, b: u64) -> Joint {
        Joint {
            id,
            kind,
            a,
            b,
            anchor_a: [5.0, 10.0, 5.0],
            anchor_b: [0.0, 0.0, 0.0],
            axis: [0.0, 1.0, 0.0],
            limits: None,
            max_length: 0.0,
            break_force: None,
        }
    }
    fn add_joint(w: &mut World, j: Joint) -> CommandResult {
        let mut r = w
            .exchange(Request {
                commands: vec![Command::AddJoint { joint: j }],
                ..Request::default()
            })
            .unwrap();
        r.results.remove(0)
    }
    fn up_tilt(rotation: [f32; 4]) -> f32 {
        // Angle between the body's local +Y and world +Y, from the unit quaternion (x,y,z,w).
        let [x, _y, z, _w] = rotation;
        let up_y = 1.0 - 2.0 * (x * x + z * z);
        up_y.clamp(-1.0, 1.0).acos()
    }

    #[test]
    fn hinge_pendulum_stays_in_its_plane() {
        let mut w = World::new(Config::default()).unwrap();
        let mut b = cube(1, 10.0, 2500.0);
        b.position = [6.0, 10.0, 5.0];
        b.parts[0].size = [0.5; 3];
        spawn(&mut w, b);
        // Anchor point (5,10,5) = body centre + (-1,0,0): the body hangs from one point.
        let mut j = joint(10, JointKind::Hinge, None, 1);
        j.axis = [0.0, 0.0, 1.0];
        j.anchor_b = [-1.0, 0.0, 0.0];
        assert!(add_joint(&mut w, j).ok);
        let s = tick(&mut w, 40);
        let p = s.bodies[0].position;
        let dist = ((p[0] - 5.0).powi(2) + (p[1] - 10.0).powi(2) + (p[2] - 5.0).powi(2)).sqrt();
        assert!((dist - 1.0).abs() < 0.02, "pendulum length drifted: {dist}");
        assert!((p[2] - 5.0).abs() < 1e-3, "hinge left its plane: z={}", p[2]);
        assert!(p[1] < 9.8, "pendulum did not swing: y={}", p[1]);
    }

    #[test]
    fn slider_moves_only_on_its_axis_and_respects_limits() {
        let mut w = World::new(Config::default()).unwrap();
        let mut b = cube(1, 4.0, 2500.0);
        b.velocity = [5.0, 0.0, 0.0];
        spawn(&mut w, b);
        let mut j = joint(10, JointKind::Slider, None, 1);
        j.anchor_a = [5.0, 4.0, 5.0];
        j.axis = [1.0, 0.0, 0.0];
        j.limits = Some([-1.0, 1.0]);
        assert!(add_joint(&mut w, j).ok);
        let s = tick(&mut w, 60);
        let p = s.bodies[0].position;
        // The anchor is at x=5, so the displacement is p[0] - 5 and must stop at the +1 m limit.
        let displacement = p[0] - 5.0;
        assert!(
            displacement > 0.9 && displacement < 1.05,
            "slider limit missed: displacement={displacement}"
        );
        assert!((p[1] - 4.0).abs() < 0.02, "gravity leaked into the slider: y={}", p[1]);
        assert!((p[2] - 5.0).abs() < 0.02, "slider left its axis: z={}", p[2]);
    }

    #[test]
    fn rope_stops_a_fall_at_its_maximum_length() {
        let mut w = World::new(Config::default()).unwrap();
        let mut b = cube(1, 9.5, 2500.0);
        b.parts[0].size = [0.5; 3];
        spawn(&mut w, b);
        let mut j = joint(10, JointKind::Rope, None, 1);
        j.max_length = 1.0;
        assert!(add_joint(&mut w, j).ok);
        let s = tick(&mut w, 60);
        let p = s.bodies[0].position;
        let dist = ((p[0] - 5.0).powi(2) + (p[1] - 10.0).powi(2) + (p[2] - 5.0).powi(2)).sqrt();
        assert!(dist <= 1.05, "rope stretched beyond its length: {dist}");
        assert!(p[1] > 8.9 && p[1] < 9.1, "rope did not stop the fall: y={}", p[1]);
    }

    #[test]
    fn cone_twist_limits_the_tilt() {
        let mut w = World::new(Config::default()).unwrap();
        spawn(&mut w, cube(1, 9.0, 2500.0));
        let mut j = joint(10, JointKind::ConeTwist, None, 1);
        j.anchor_a = [5.0, 10.0, 5.0];
        j.anchor_b = [0.0, 1.0, 0.0];
        j.limits = Some([0.0, 0.3]);
        assert!(add_joint(&mut w, j).ok);
        // A sideways impulse above the centre starts the body tumbling.
        w.exchange(Request {
            commands: vec![Command::Impulse {
                id: 1,
                impulse: [0.0, 0.0, 10.0],
                point: Some([5.5, 9.5, 5.0]),
            }],
            ..Request::default()
        })
        .unwrap();
        let mut max_tilt = 0.0f32;
        for _ in 0..40 {
            let s = tick(&mut w, 1);
            max_tilt = max_tilt.max(up_tilt(s.bodies[0].rotation));
        }
        assert!(max_tilt < 0.45, "cone limit ignored: max tilt {max_tilt} rad (limit 0.3)");
    }

    #[test]
    fn joint_breaks_under_overload_and_is_reported_once() {
        let mut w = World::new(Config::default()).unwrap();
        let mut b = cube(1, 9.0, 20000.0); // 20 t: far beyond the joint's 1 kN limit
        b.parts[0].size = [1.0; 3];
        spawn(&mut w, b);
        let mut j = joint(10, JointKind::Hinge, None, 1);
        j.anchor_b = [0.0, 1.0, 0.0];
        j.axis = [0.0, 0.0, 1.0];
        j.break_force = Some(1000.0);
        assert!(add_joint(&mut w, j).ok);
        let s = tick(&mut w, 1);
        assert_eq!(s.broken_joints, vec![10]);
        assert_eq!(s.joint_count, 0);
        let next = tick(&mut w, 1);
        assert!(next.broken_joints.is_empty(), "break reported twice");
    }

    #[test]
    fn removing_a_body_removes_its_joints() {
        let mut w = World::new(Config::default()).unwrap();
        spawn(&mut w, cube(1, 9.0, 2500.0));
        assert!(add_joint(&mut w, joint(10, JointKind::Hinge, None, 1)).ok);
        let s = w
            .exchange(Request {
                commands: vec![Command::RemoveBody { id: 1 }],
                ..Request::default()
            })
            .unwrap();
        assert_eq!(s.joint_count, 0);
    }

    #[test]
    fn invalid_joints_are_rejected_without_side_effects() {
        let mut w = World::new(Config::default()).unwrap();
        spawn(&mut w, cube(1, 9.0, 2500.0));
        let mut zero_axis = joint(10, JointKind::Hinge, None, 1);
        zero_axis.axis = [0.0; 3];
        assert!(!add_joint(&mut w, zero_axis).ok);
        let mut no_length = joint(11, JointKind::Rope, None, 1);
        no_length.max_length = 0.0;
        assert!(!add_joint(&mut w, no_length).ok);
        assert!(!add_joint(&mut w, joint(12, JointKind::Hinge, Some(1), 1)).ok);
        assert!(!add_joint(&mut w, joint(13, JointKind::Hinge, None, 99)).ok);
        let s = w.snapshot(vec![]);
        assert_eq!(s.joint_count, 0);
    }

    #[test]
    fn saved_state_round_trips_through_json_and_resumes_identically() {
        let mut a = World::new(Config::default()).unwrap();
        let mut falling = cube(1, 20.0, 2500.0);
        falling.velocity = [1.0, 0.0, 0.0];
        spawn(&mut a, falling);
        let mut pend = cube(2, 10.0, 2500.0);
        pend.position = [6.0, 10.0, 5.0];
        pend.parts[0].size = [0.5; 3];
        spawn(&mut a, pend);
        let mut j = joint(10, JointKind::Hinge, None, 2);
        j.axis = [0.0, 0.0, 1.0];
        j.anchor_b = [-1.0, 0.0, 0.0];
        assert!(add_joint(&mut a, j).ok);
        tick(&mut a, 20);
        let state = a
            .exchange(Request {
                commands: vec![Command::ExportState],
                ..Request::default()
            })
            .unwrap()
            .results
            .remove(0)
            .state
            .expect("export returns state");
        let json = serde_json::to_string(&state).unwrap();
        let restored: SavedState = serde_json::from_str(&json).unwrap();
        let mut b = World::new(Config::default()).unwrap();
        let r = b
            .exchange(Request {
                commands: vec![Command::ImportState { state: restored }],
                ..Request::default()
            })
            .unwrap()
            .results
            .remove(0);
        assert!(r.ok, "{}", r.message);
        let sa = tick(&mut a, 40);
        let sb = tick(&mut b, 40);
        assert_eq!(sa.bodies.len(), sb.bodies.len());
        assert_eq!(sb.joint_count, 1);
        for (x, y) in sa.bodies.iter().zip(&sb.bodies) {
            for k in 0..3 {
                assert!(
                    (x.position[k] - y.position[k]).abs() < 1e-3,
                    "body {} diverged after restore: {:?} vs {:?}",
                    x.id,
                    x.position,
                    y.position
                );
            }
        }
    }

    #[test]
    fn failed_import_keeps_the_previous_world() {
        let mut w = World::new(Config::default()).unwrap();
        spawn(&mut w, cube(1, 9.0, 2500.0));
        let bad = SavedState {
            format: SAVE_FORMAT,
            bodies: vec![],
            springs: vec![],
            joints: vec![joint(10, JointKind::Hinge, None, 42)],
            wind: [0.0; 3],
        };
        let r = w
            .exchange(Request {
                commands: vec![Command::ImportState { state: bad }],
                ..Request::default()
            })
            .unwrap()
            .results
            .remove(0);
        assert!(!r.ok);
        let s = w.snapshot(vec![]);
        assert_eq!(s.bodies.len(), 1, "failed import must not clear the world");
        let wrong_format = SavedState {
            format: 99,
            bodies: vec![],
            springs: vec![],
            joints: vec![],
            wind: [0.0; 3],
        };
        let r = w
            .exchange(Request {
                commands: vec![Command::ImportState { state: wrong_format }],
                ..Request::default()
            })
            .unwrap()
            .results
            .remove(0);
        assert!(!r.ok && r.message.contains("format"));
    }

    #[test]
    fn solver_settings_are_validated() {
        let zero_iterations = Config {
            solver_iterations: 0,
            ..Config::default()
        };
        assert!(World::new(zero_iterations).is_err());
        let too_many_substeps = Config {
            ccd_substeps: 9,
            ..Config::default()
        };
        assert!(World::new(too_many_substeps).is_err());
        assert!(World::new(Config::default()).is_ok());
    }

    /// Manual benchmark: `cargo test --release --lib bench_solver -- --ignored --nocapture`.
    /// Ten 1 m cubes stacked on the ground for 3 s. The correct top-centre height is 9.5 m.
    #[test]
    #[ignore]
    fn bench_solver_settings() {
        for &iterations in &[1usize, 2, 4, 8] {
            for &ccd in &[1usize, 4] {
                let config = Config {
                    solver_iterations: iterations,
                    ccd_substeps: ccd,
                    ..Config::default()
                };
                let mut w = World::new(config).unwrap();
                w.exchange(Request {
                    terrain: Some(vec![TerrainBox {
                        center: [5.0, -0.5, 5.0],
                        size: [20.0, 1.0, 20.0],
                    }]),
                    ..Request::default()
                })
                .unwrap();
                for i in 0..10u64 {
                    spawn(&mut w, cube(i + 1, 0.5 + i as f32 * 1.02, 2500.0));
                }
                let started = Instant::now();
                let s = tick(&mut w, 60);
                let elapsed_ms = started.elapsed().as_secs_f64() * 1000.0;
                let top = s.bodies.iter().map(|b| b.position[1]).fold(f32::MIN, f32::max);
                println!(
                    "iterations={iterations} ccd={ccd} time_3s={elapsed_ms:.1}ms top_error={:.4}m",
                    (top - 9.5).abs()
                );
            }
        }
    }

    #[test]
    fn json_requests_from_the_jvm_drive_joints_and_state() {
        // The same JSON shapes that Lab.java sends through NativeBridge.exchange.
        let mut w = World::new(Config::default()).unwrap();
        let body = r#"{"commands":[{"op":"add_body","body":{"id":1,"position":[5,9,5],
            "parts":[{"offset":[0,0,0],"size":[1,1,1],"density":2500}]}}]}"#;
        let r: Request = serde_json::from_str(body).unwrap();
        assert!(w.exchange(r).unwrap().results[0].ok);
        let joint = r#"{"commands":[{"op":"add_joint","joint":{"id":10,"kind":"hinge",
            "a":null,"b":1,"anchor_a":[5,10,5],"anchor_b":[0,1,0],"axis":[0,0,1],
            "break_force":1e6}}]}"#;
        let r: Request = serde_json::from_str(joint).unwrap();
        let out = w.exchange(r).unwrap();
        assert!(out.results[0].ok, "{}", out.results[0].message);
        assert_eq!(out.joint_count, 1);
        let export: Request = serde_json::from_str(r#"{"commands":[{"op":"export_state"}]}"#).unwrap();
        let state = w.exchange(export).unwrap().results.remove(0).state.unwrap();
        let text = serde_json::to_string(&serde_json::json!({ "commands": [{ "op": "import_state", "state": state }] })).unwrap();
        let r: Request = serde_json::from_str(&text).unwrap();
        let mut fresh = World::new(Config::default()).unwrap();
        let out = fresh.exchange(r).unwrap();
        assert!(out.results[0].ok, "{}", out.results[0].message);
        assert_eq!(out.joint_count, 1);
        assert_eq!(out.bodies.len(), 1);
    }
}

#[cfg(test)]
mod terrain_patch_tests {
    use super::*;

    #[test]
    fn set_pose_moves_rotates_and_stops_a_body() {
        let mut w = World::new(Config::default()).unwrap();
        step(&mut w, vec![Command::AddBody { body: falling_cube(1, 30.0, -90.0) }], 0.0);
        // Quarter turn about Y: quaternion x,y,z,w = (0, sin 45, 0, cos 45).
        let half = std::f32::consts::FRAC_1_SQRT_2;
        let s = step(
            &mut w,
            vec![Command::SetPose { id: 1, position: [7.0, 12.0, 6.0], rotation: [0.0, half, 0.0, half] }],
            0.05,
        );
        let b = &s.bodies[0];
        assert!((b.position[0] - 7.0).abs() < 0.05, "x={}", b.position[0]);
        assert!((b.position[1] - 12.0).abs() < 0.05, "y={} (velocity must be zeroed)", b.position[1]);
        assert!((b.position[2] - 6.0).abs() < 0.05, "z={}", b.position[2]);
        assert!((b.rotation[1].abs() - half).abs() < 0.05, "rotation {:?}", b.rotation);
        let bad = w.exchange(Request {
            commands: vec![Command::SetPose { id: 1, position: [0.0; 3], rotation: [0.0; 4] }],
            ..Request::default()
        });
        assert!(bad.is_ok(), "a rejected command must not fail the whole request");
        assert!(w.exchange(Request {
            commands: vec![Command::SetPose { id: 99, position: [0.0; 3], rotation: [0.0, 0.0, 0.0, 1.0] }],
            ..Request::default()
        }).is_ok());
    }

    fn falling_cube(id: u64, y: f32, vy: f32) -> Body {
        Body {
            id,
            position: [5.0, y, 5.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
            velocity: [0.0, vy, 0.0],
            parts: vec![Part {
                offset: [0.0; 3],
                size: [1.0; 3],
                density: 2500.0,
                friction: 0.6,
                restitution: 0.0,
                block: "minecraft:stone".into(),
            }],
            wing: None,
        }
    }
    /// A slab whose top face is at `top`.
    fn plate(key: u64, top: f32, thickness: f32) -> KeyedTerrainBox {
        KeyedTerrainBox {
            key,
            center: [5.0, top - thickness * 0.5, 5.0],
            size: [20.0, thickness, 20.0],
        }
    }
    /// Applies `commands`, then advances `seconds` in chunks of at most 0.1 s (the request limit).
    /// The results of `commands` are kept in the returned snapshot.
    fn step(w: &mut World, commands: Vec<Command>, seconds: f32) -> Snapshot {
        let mut out = w
            .exchange(Request { dt: 0.0, commands, ..Request::default() })
            .unwrap();
        let results = std::mem::take(&mut out.results);
        let mut left = seconds;
        while left > 1e-6 {
            let dt = left.min(0.1);
            out = w.exchange(Request { dt, ..Request::default() }).unwrap();
            left -= dt;
        }
        out.results = results;
        out
    }

    #[test]
    fn keyed_plate_supports_a_body_and_its_removal_releases_it() {
        let mut w = World::new(Config::default()).unwrap();
        let s = step(
            &mut w,
            vec![
                Command::AddBody { body: falling_cube(1, 4.0, 0.0) },
                Command::PatchTerrain { add: vec![plate(7, 0.0, 0.5)], remove: vec![] },
            ],
            0.0,
        );
        assert!(s.results.iter().all(|r| r.ok), "{:?}", s.results.iter().map(|r| &r.message).collect::<Vec<_>>());
        assert_eq!(s.static_colliders, 1);
        let y = step(&mut w, vec![], 2.0).bodies[0].position[1];
        assert!(y > 0.45 && y < 0.6, "body not resting on the plate: centre y = {y}");
        let s = step(&mut w, vec![Command::PatchTerrain { add: vec![], remove: vec![7] }], 1.0);
        assert!(s.results[0].ok);
        assert_eq!(s.static_colliders, 0);
        let y2 = s.bodies[0].position[1];
        assert!(y2 < y - 0.5, "removing the plate did not release the body: y = {y2}");
    }

    #[test]
    fn plate_added_mid_flight_stops_a_fast_body() {
        // The plate appears while the cube is already falling at 90 m/s (protocol maximum).
        // Its top is at 12 m, far below the start, so the body must hit it between steps.
        let mut w = World::new(Config::default()).unwrap();
        step(&mut w, vec![Command::AddBody { body: falling_cube(1, 30.0, -90.0) }], 0.0);
        step(&mut w, vec![], 0.1);
        let s = step(&mut w, vec![Command::PatchTerrain { add: vec![plate(9, 12.0, 0.05)], remove: vec![] }], 1.0);
        assert!(s.results[0].ok);
        let y = s.bodies[0].position[1];
        assert!(y > 12.0 && y < 13.5, "fast body passed through the new plate: centre y = {y}");
    }

    #[test]
    fn invalid_patch_changes_nothing_and_unknown_removal_is_a_no_op() {
        let mut w = World::new(Config::default()).unwrap();
        let s = step(&mut w, vec![Command::PatchTerrain { add: vec![plate(1, 0.0, 0.5)], remove: vec![] }], 0.0);
        assert!(s.results[0].ok);
        // Duplicate key in one patch: rejected, and the removal of key 1 must not happen.
        let s = step(
            &mut w,
            vec![Command::PatchTerrain { add: vec![plate(2, 5.0, 0.5), plate(2, 6.0, 0.5)], remove: vec![1] }],
            0.0,
        );
        assert!(!s.results[0].ok);
        assert_eq!(s.static_colliders, 1);
        // Upsert of an existing key keeps the count; unknown removal is ignored.
        let s = step(
            &mut w,
            vec![
                Command::PatchTerrain { add: vec![plate(1, 3.0, 0.5)], remove: vec![] },
                Command::PatchTerrain { add: vec![], remove: vec![99] },
            ],
            0.0,
        );
        assert!(s.results.iter().all(|r| r.ok));
        assert_eq!(s.static_colliders, 1);
    }
}

