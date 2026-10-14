use serde::{Deserialize, Serialize};

pub const ABI_VERSION: i32 = 1;
pub type V3 = [f32; 3];
pub fn zero() -> V3 {
    [0.0; 3]
}
pub fn identity() -> [f32; 4] {
    [0.0, 0.0, 0.0, 1.0]
}
fn friction() -> f32 {
    0.6
}
fn restitution() -> f32 {
    0.05
}
fn block() -> String {
    "minecraft:oak_planks".into()
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub gravity: f32,
    pub air_density: f32,
    pub water_density: f32,
    pub step_hz: u32,
    pub max_bodies: usize,
    /// Rapier velocity-solver iterations per step (1..=8).
    #[serde(default = "solver_iterations")]
    pub solver_iterations: usize,
    /// Rapier CCD substeps per step (1..=8). Higher values catch faster thin contacts.
    #[serde(default = "ccd_substeps")]
    pub ccd_substeps: usize,
}
fn solver_iterations() -> usize {
    4
}
fn ccd_substeps() -> usize {
    8
}
impl Default for Config {
    fn default() -> Self {
        Self {
            gravity: 9.81,
            air_density: 1.225,
            water_density: 998.2,
            step_hz: 120,
            max_bodies: 64,
            solver_iterations: solver_iterations(),
            ccd_substeps: ccd_substeps(),
        }
    }
}
impl Config {
    pub fn validate(&self) -> Result<(), String> {
        bounded(self.gravity, 0.1, 30.0, "gravity")?;
        bounded(self.air_density, 0.0, 20.0, "air_density")?;
        bounded(self.water_density, 100.0, 2000.0, "water_density")?;
        if !(30..=240).contains(&self.step_hz) {
            return Err("step_hz must be 30..240".into());
        }
        // 0 = no body-count limit (the Java side keeps a cell budget per world instead).
        if self.max_bodies > 10_000 {
            return Err("max_bodies must be 0 (no limit) or at most 10000".into());
        }
        if !(1..=8).contains(&self.solver_iterations) {
            return Err("solver_iterations must be 1..8".into());
        }
        if !(1..=8).contains(&self.ccd_substeps) {
            return Err("ccd_substeps must be 1..8".into());
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Part {
    pub offset: V3,
    pub size: V3,
    pub density: f32,
    #[serde(default = "friction")]
    pub friction: f32,
    #[serde(default = "restitution")]
    pub restitution: f32,
    #[serde(default = "block")]
    pub block: String,
}
impl Part {
    pub fn volume(&self) -> f32 {
        self.size.iter().product()
    }
    pub fn validate(&self) -> Result<(), String> {
        vector(self.offset, 32.0, "part.offset")?;
        for s in self.size {
            bounded(s, 0.02, 16.0, "part.size")?;
        }
        bounded(self.density, 1.0, 20000.0, "part.density")?;
        bounded(self.friction, 0.0, 2.0, "part.friction")?;
        bounded(self.restitution, 0.0, 1.0, "part.restitution")?;
        if self.block.len() > 128 || !self.block.contains(':') {
            return Err("invalid block identifier".into());
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Wing {
    pub center: V3,
    pub span: V3,
    pub chord: V3,
    pub normal: V3,
    pub area: f32,
    pub aspect_ratio: f32,
}
impl Wing {
    pub fn validate(&self) -> Result<(), String> {
        vector(self.center, 32.0, "wing.center")?;
        for axis in [self.span, self.chord, self.normal] {
            vector(axis, 1.01, "wing.axis")?;
            let n = axis.iter().map(|x| x * x).sum::<f32>();
            if (n - 1.0).abs() > 0.01 {
                return Err("wing axes must have unit length".into());
            }
        }
        for (a, b) in [
            (self.span, self.chord),
            (self.span, self.normal),
            (self.chord, self.normal),
        ] {
            if a.iter().zip(b).map(|(x, y)| x * y).sum::<f32>().abs() > 0.01 {
                return Err("wing axes must be orthogonal".into());
            }
        }
        bounded(self.area, 0.01, 256.0, "wing.area")?;
        bounded(self.aspect_ratio, 0.5, 30.0, "wing.aspect_ratio")
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Body {
    pub id: u64,
    pub position: V3,
    #[serde(default = "identity")]
    pub rotation: [f32; 4], // x,y,z,w
    #[serde(default = "zero")]
    pub velocity: V3,
    pub parts: Vec<Part>,
    #[serde(default)]
    pub wing: Option<Wing>,
}
impl Body {
    pub fn validate(&self) -> Result<(), String> {
        if self.id == 0 || self.id > i64::MAX as u64 {
            return Err("body id must be positive signed 64-bit".into());
        }
        vector(self.position, 128.0, "body.position")?;
        vector(self.velocity, 100.0, "body.velocity")?;
        let norm_squared = self.rotation.iter().map(|x| x * x).sum::<f32>();
        if self
            .rotation
            .iter()
            .any(|x| !x.is_finite() || x.abs() > 10000.0)
            || !norm_squared.is_finite()
            || norm_squared < 1e-8
        {
            return Err("invalid quaternion".into());
        }
        if self.parts.is_empty() || self.parts.len() > 64 {
            return Err("a body needs 1..64 parts".into());
        }
        for p in &self.parts {
            p.validate()?;
        }
        if self
            .parts
            .iter()
            .map(|p| p.volume() * p.density)
            .sum::<f32>()
            < 0.05
        {
            return Err("body mass must be at least 0.05 kg".into());
        }
        // Overlapping colliders would double-count material and displaced volume.
        for (i, a) in self.parts.iter().enumerate() {
            for b in self.parts.iter().skip(i + 1) {
                if (0..3).all(|k| {
                    (a.offset[k] - b.offset[k]).abs() < 0.5 * (a.size[k] + b.size[k]) - 1e-4
                }) {
                    return Err("body parts must not overlap".into());
                }
            }
        }
        if let Some(w) = &self.wing {
            w.validate()?;
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TerrainBox {
    pub center: V3,
    pub size: V3,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct KeyedTerrainBox {
    pub key: u64,
    pub center: V3,
    pub size: V3,
}
impl KeyedTerrainBox {
    pub fn as_box(&self) -> TerrainBox {
        TerrainBox { center: self.center, size: self.size }
    }
}
impl TerrainBox {
    pub fn validate(&self) -> Result<(), String> {
        vector(self.center, 256.0, "terrain.center")?;
        for s in self.size {
            bounded(s, 0.001, 64.0, "terrain.size")?;
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WaterCell {
    pub cell: [i32; 3],
    pub height: f32,
    #[serde(default = "zero")]
    pub velocity: V3,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TankSpec {
    pub origin: V3, // bottom, not the free surface
    pub nx: usize,
    pub nz: usize,
    pub dx: f32,
    pub depth_left: f32,
    pub depth_right: f32,
    #[serde(default)]
    pub viscosity: f32,
    #[serde(default)]
    pub damping: f32,
    /// Manning roughness n (s/m^(1/3)); 0 disables bed friction.
    #[serde(default)]
    pub roughness: f32,
}
impl TankSpec {
    pub fn validate(&self) -> Result<(), String> {
        vector(self.origin, 64.0, "tank.origin")?;
        if !(2..=32).contains(&self.nx) || !(2..=32).contains(&self.nz) {
            return Err("tank dimensions must be 2..32".into());
        }
        bounded(self.dx, 0.25, 2.0, "tank.dx")?;
        bounded(self.depth_left, 0.0, 4.0, "tank.depth_left")?;
        bounded(self.depth_right, 0.0, 4.0, "tank.depth_right")?;
        bounded(self.viscosity, 0.0, 0.1, "tank.viscosity")?;
        bounded(self.damping, 0.0, 5.0, "tank.damping")?;
        bounded(self.roughness, 0.0, 0.2, "tank.roughness")
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Spring {
    pub id: u64,
    pub a: Option<u64>, // None = fixed world anchor
    pub b: u64,
    pub anchor_a: V3, // local on a, world-space if a=None
    pub anchor_b: V3, // local on b
    pub rest_length: f32,
    pub stiffness: f32,
    pub damping: f32,
}

/// One converted block: integer coordinate `cell` occupies `[cell, cell+1]^3`.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AssemblyCell {
    pub cell: [i32; 3],
    pub density: f32,
    #[serde(default = "friction")]
    pub friction: f32,
    #[serde(default = "restitution")]
    pub restitution: f32,
    #[serde(default = "block")]
    pub block: String,
    /// Sub-cell collision boxes in cell-local units [0,1]^3 as [min_x,min_y,min_z,max_x,max_y,max_z].
    /// Absent = full cube (greedy-merged with its neighbours). Present = exactly these boxes;
    /// an empty list means the cell has no collider (it is carried as part of the body only).
    #[serde(default)]
    pub boxes: Option<Vec<[f32; 6]>>,
}
impl AssemblyCell {
    pub fn validate(&self) -> Result<(), String> {
        if self.cell.iter().any(|x| x.unsigned_abs() > 256) {
            return Err("assembly cell out of bounds (|coordinate| <= 256)".into());
        }
        if let Some(list) = &self.boxes {
            if list.len() > 8 {
                return Err("cell.boxes: at most 8 boxes per cell".into());
            }
            for b in list {
                if b.iter().any(|v| !v.is_finite() || *v < 0.0 || *v > 1.0) {
                    return Err("cell.boxes: coordinates must lie in [0,1]".into());
                }
                if (0..3).any(|a| b[a + 3] - b[a] < 1e-3) {
                    return Err("cell.boxes: a box is thinner than 1 mm".into());
                }
            }
        }
        bounded(self.density, 1.0, 20000.0, "cell.density")?;
        bounded(self.friction, 0.0, 2.0, "cell.friction")?;
        bounded(self.restitution, 0.0, 1.0, "cell.restitution")?;
        if self.block.len() > 128 || !self.block.contains(':') {
            return Err("invalid block identifier".into());
        }
        Ok(())
    }
}

/// Joint kinds. `axis` is expressed in the local frame of both bodies and must
/// describe the same physical direction when the joint is created.
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum JointKind {
    /// Rotation about `axis`. `limits` = allowed angle range in radians.
    Hinge,
    /// Translation along `axis`. `limits` = allowed displacement range in metres.
    Slider,
    /// Rotation around the anchor, limited to a cone. `limits[1]` = half-angle (radians, 0..pi/2).
    ConeTwist,
    /// Maximum distance between anchors (a rope or chain link). `max_length` in metres.
    Rope,
}
fn joint_axis() -> V3 {
    [0.0, 1.0, 0.0]
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Joint {
    pub id: u64,
    pub kind: JointKind,
    /// None anchors the joint to the world (fixed point at `anchor_a`, world space).
    pub a: Option<u64>,
    pub b: u64,
    pub anchor_a: V3,
    pub anchor_b: V3,
    #[serde(default = "joint_axis")]
    pub axis: V3,
    #[serde(default)]
    pub limits: Option<[f32; 2]>,
    #[serde(default)]
    pub max_length: f32,
    /// Joint breaks when the constraint force exceeds this value (newtons).
    #[serde(default)]
    pub break_force: Option<f32>,
}
impl Joint {
    pub fn validate(&self) -> Result<(), String> {
        vector(self.anchor_a, 128.0, "joint.anchor_a")?;
        vector(self.anchor_b, 32.0, "joint.anchor_b")?;
        let n2 = self.axis.iter().map(|x| x * x).sum::<f32>();
        if !n2.is_finite() || n2 < 1e-6 || self.axis.iter().any(|x| x.abs() > 1.0) {
            return Err("joint.axis must be a non-zero vector with components in [-1, 1]".into());
        }
        match self.kind {
            JointKind::Hinge => {
                let [lo, hi] = self.limits.unwrap_or([-std::f32::consts::PI, std::f32::consts::PI]);
                bounded(lo, -std::f32::consts::PI, std::f32::consts::PI, "hinge.lo")?;
                bounded(hi, -std::f32::consts::PI, std::f32::consts::PI, "hinge.hi")?;
                if lo > hi {
                    return Err("hinge limits must satisfy lo <= hi".into());
                }
            }
            JointKind::Slider => {
                let [lo, hi] = self.limits.unwrap_or([-64.0, 64.0]);
                bounded(lo, -64.0, 64.0, "slider.lo")?;
                bounded(hi, -64.0, 64.0, "slider.hi")?;
                if lo > hi {
                    return Err("slider limits must satisfy lo <= hi".into());
                }
            }
            JointKind::ConeTwist => {
                let half = self.limits.map(|l| l[1]).unwrap_or(0.5);
                bounded(half, 0.0, std::f32::consts::FRAC_PI_2, "cone.half_angle")?;
            }
            JointKind::Rope => bounded(self.max_length, 0.01, 64.0, "rope.max_length")?,
        }
        if let Some(f) = self.break_force {
            bounded(f, 1.0, 10_000_000.0, "joint.break_force")?;
        }
        Ok(())
    }
}

/// Persisted body: spec plus the dynamic state needed to resume motion.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SavedBody {
    pub body: Body,
    pub angular_velocity: V3,
}
/// Versioned dynamic state of the world. Environment (terrain, water, tank) is
/// deliberately excluded: Java re-sends it on every scan.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SavedState {
    pub format: u32,
    pub bodies: Vec<SavedBody>,
    pub springs: Vec<Spring>,
    pub joints: Vec<Joint>,
    pub wind: V3,
}
pub const SAVE_FORMAT: u32 = 1;

/// Box that stands in for a player or living entity (lab-local coordinates, no rotation).
/// It pushes dynamic bodies out of its volume and receives push-out corrections back.
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ActorBox {
    /// Caller-chosen key (Java uses the entity id). Must be non-zero.
    pub key: u64,
    pub center: V3,
    /// Half extents of the box.
    pub half: V3,
    #[serde(default = "actor_mass")]
    pub mass: f32,
}
fn actor_mass() -> f32 {
    80.0
}
impl ActorBox {
    pub fn validate(&self) -> Result<(), String> {
        if self.key == 0 {
            return Err("actor key must be non-zero".into());
        }
        vector(self.center, 256.0, "actor.center")?;
        for h in self.half {
            bounded(h, 0.05, 4.0, "actor.half")?;
        }
        bounded(self.mass, 1.0, 2000.0, "actor.mass")
    }
}

/// Spring-damper hold of one body point towards a target (gravity gun).
/// Gains are derived from the body mass: `frequency` (rad/s) and `damping_ratio` (dimensionless),
/// force is clamped to `max_accel * mass`.
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Grab {
    pub id: u64,
    /// Grab point in the body frame.
    pub point: V3,
    /// Target of the grab point, lab-local world coordinates.
    pub target: V3,
    #[serde(default = "grab_frequency")]
    pub frequency: f32,
    #[serde(default = "grab_damping_ratio")]
    pub damping_ratio: f32,
    #[serde(default = "grab_max_accel")]
    pub max_accel: f32,
}
fn grab_frequency() -> f32 {
    6.0
}
fn grab_damping_ratio() -> f32 {
    0.7
}
fn grab_max_accel() -> f32 {
    60.0
}
impl Grab {
    pub fn validate(&self) -> Result<(), String> {
        vector(self.point, 256.0, "grab.point")?;
        vector(self.target, 256.0, "grab.target")?;
        bounded(self.frequency, 0.1, 40.0, "grab.frequency")?;
        bounded(self.damping_ratio, 0.0, 3.0, "grab.damping_ratio")?;
        bounded(self.max_accel, 0.0, 1000.0, "grab.max_accel")
    }
}

/// Result for one actor after the last fixed step.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ActorContact {
    pub actor: u64,
    /// Total push-out of the actor (metres, lab-local axes). Java moves the entity by it.
    pub correction: V3,
    /// Mean velocity of the bodies the actor stands on (m/s). Zero when not standing.
    pub carry: V3,
    /// True when at least one body pushes the actor upwards (the actor stands on it).
    pub standing: bool,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
pub enum Command {
    /// Converts a set of unique cells into ONE rigid body (greedy-merged colliders).
    AssembleBody {
        id: u64,
        cells: Vec<AssemblyCell>,
    },
    /// Adds cells to an existing body; the body must be grid-aligned (multiple of 90 degrees).
    AttachCells {
        id: u64,
        cells: Vec<AssemblyCell>,
    },
    /// Removes a grid-aligned body and returns its cells in the command result.
    /// `snap`: a body resting off-grid by a whole-block-rounded translation is accepted;
    /// its cells are rounded to the nearest block. Rotation must still be a multiple of 90 degrees.
    DisassembleBody {
        id: u64,
        #[serde(default)]
        snap: bool,
    },
    /// Incremental terrain update. `add` upserts boxes under caller-chosen keys; removing an
    /// unknown key is a no-op. The whole command is validated before anything changes.
    PatchTerrain {
        #[serde(default)]
        add: Vec<KeyedTerrainBox>,
        #[serde(default)]
        remove: Vec<u64>,
    },
    AddBody {
        body: Body,
    },
    RemoveBody {
        id: u64,
    },
    /// Sets a body's pose in the Lab frame and zeroes its velocities (grid alignment in the gravity gun).
    /// `rotation` is the quaternion x, y, z, w; it is normalised before use.
    SetPose {
        id: u64,
        position: V3,
        rotation: [f32; 4],
    },
    Impulse {
        id: u64,
        impulse: V3,
        #[serde(default)]
        point: Option<V3>,
    },
    SetWind {
        velocity: V3,
    },
    SetTank {
        tank: TankSpec,
    },
    RemoveTank,
    AddSpring {
        spring: Spring,
    },
    RemoveSpring {
        id: u64,
    },
    AddJoint {
        joint: Joint,
    },
    RemoveJoint {
        id: u64,
    },
    /// Returns the dynamic state in the command result (`state`).
    ExportState,
    /// Replaces all bodies, springs and joints with a saved state (environment kept).
    ImportState {
        state: SavedState,
    },
    Pause {
        paused: bool,
    },
}

#[derive(Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Request {
    pub dt: f32,
    pub terrain: Option<Vec<TerrainBox>>,
    pub water_cells: Option<Vec<WaterCell>>,
    /// Replaces the actor set. `None` keeps the previous set.
    pub actors: Option<Vec<ActorBox>>,
    /// Replaces the active grabs. `None` keeps the previous grabs.
    pub grabs: Option<Vec<Grab>>,
    pub commands: Vec<Command>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct BodyState {
    pub id: u64,
    pub position: V3,
    pub rotation: [f32; 4],
    pub velocity: V3,
    pub angular_velocity: V3,
    pub mass: f32,
    pub parts: Vec<Part>,
    pub buoyancy_force: V3,
    pub air_force: V3,
    /// World-space axis-aligned bounds of all colliders (hitbox for presentation/interaction).
    #[serde(default)]
    pub aabb_min: V3,
    #[serde(default)]
    pub aabb_max: V3,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct TankState {
    pub spec: TankSpec,
    pub depths: Vec<f32>, // row-major: z*nx+x
    pub volume: f64,
    pub relative_volume_error: f64,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct CommandResult {
    pub ok: bool,
    pub message: String,
    /// Present only for successful disassembly (additive field).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cells: Option<Vec<[i32; 3]>>,
    /// Present only for a successful `ExportState`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub state: Option<SavedState>,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Snapshot {
    pub abi: i32,
    pub sim_seconds: f64,
    pub fixed_steps: u64,
    pub paused: bool,
    pub bodies: Vec<BodyState>,
    pub tank: Option<TankState>,
    pub wind: V3,
    pub last_step_us: u64,
    pub static_colliders: usize,
    pub results: Vec<CommandResult>,
    #[serde(default)]
    pub joint_count: usize,
    /// Joints that broke since the previous snapshot.
    #[serde(default)]
    pub broken_joints: Vec<u64>,
    /// Actor contacts computed by the last fixed step. Identified by `contact_step`.
    #[serde(default)]
    pub contacts: Vec<ActorContact>,
    /// Fixed-step counter at which `contacts` were computed (applies each result once).
    #[serde(default)]
    pub contact_step: u64,
}

pub fn bounded(v: f32, min: f32, max: f32, name: &str) -> Result<(), String> {
    if !v.is_finite() || v < min || v > max {
        Err(format!("{name} must be finite and in [{min}, {max}]"))
    } else {
        Ok(())
    }
}
pub fn vector(v: V3, bound: f32, name: &str) -> Result<(), String> {
    for x in v {
        bounded(x, -bound, bound, name)?;
    }
    Ok(())
}

#[cfg(test)]
mod max_bodies_tests {
    use super::Config;

    #[test]
    fn zero_max_bodies_means_no_limit() {
        let config = Config { max_bodies: 0, ..Config::default() };
        assert!(config.validate().is_ok());
    }

    #[test]
    fn max_bodies_above_ten_thousand_is_rejected() {
        let config = Config { max_bodies: 10_001, ..Config::default() };
        assert!(config.validate().is_err());
    }
}
