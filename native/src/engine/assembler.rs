//! Physics assembler: converts block cells into one rigid body and back.
//!
//! Rust owns the geometry. Body poses are Rapier poses, and part offsets are
//! relative to the body origin (the same convention as `Body::parts`).
//! Attach/disassemble require a grid-aligned body (rotation = multiple of 90 degrees),
//! so every cell maps exactly onto an axis-aligned box in the body frame.
use super::World;
use crate::{
    assembler::{merge_boxes, split_long, GridBox},
    forces::{self, v},
    protocol::*,
};
use rapier3d::{
    na::{Isometry3, Matrix3, Vector3},
    prelude::*,
};
use std::collections::{BTreeMap, BTreeSet};

const SNAP_TOLERANCE: f32 = 5e-3;
const MAX_PARTS_PER_BODY: usize = 64;
const MAX_PARTS_TOTAL: usize = 256;
const MAX_CELLS_PER_COMMAND: usize = 4096;
const MAX_DISASSEMBLED_CELLS: usize = 16384;
const ALIGN_TOLERANCE: f32 = 1e-3;

/// Material shared by all cells of one merged group. Floats are compared bitwise.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct MaterialKey {
    density: u32,
    friction: u32,
    restitution: u32,
    block: String,
}
impl MaterialKey {
    fn of(c: &AssemblyCell) -> Self {
        Self {
            density: c.density.to_bits(),
            friction: c.friction.to_bits(),
            restitution: c.restitution.to_bits(),
            block: c.block.clone(),
        }
    }
}

fn part_for(key: &MaterialKey, size: [f32; 3], offset: [f32; 3]) -> Part {
    Part {
        offset,
        size,
        density: f32::from_bits(key.density),
        friction: f32::from_bits(key.friction),
        restitution: f32::from_bits(key.restitution),
        block: key.block.clone(),
    }
}

/// One collider in world-grid units: centre and size.
struct Piece {
    key: MaterialKey,
    centre: [f32; 3],
    size: [f32; 3],
}

/// Validates cells, then builds colliders. Full cells are greedy-merged per material into grid
/// boxes; a cell with sub-cell shapes contributes one exact box per shape box (no merging).
fn merged_pieces(cells: &[AssemblyCell]) -> Result<Vec<Piece>, String> {
    if cells.is_empty() {
        return Err("no cells given".into());
    }
    if cells.len() > MAX_CELLS_PER_COMMAND {
        return Err(format!("at most {MAX_CELLS_PER_COMMAND} cells per command"));
    }
    let mut all = BTreeSet::new();
    let mut full: BTreeMap<MaterialKey, Vec<[i32; 3]>> = BTreeMap::new();
    let mut pieces = Vec::new();
    for c in cells {
        c.validate()?;
        if !all.insert(c.cell) {
            return Err(format!("duplicate cell {:?}", c.cell));
        }
        match &c.boxes {
            None => full.entry(MaterialKey::of(c)).or_default().push(c.cell),
            Some(list) => {
                for b in list {
                    let centre = std::array::from_fn(|a| c.cell[a] as f32 + (b[a] + b[a + 3]) * 0.5);
                    let size = std::array::from_fn(|a| b[a + 3] - b[a]);
                    pieces.push(Piece { key: MaterialKey::of(c), centre, size });
                }
            }
        }
    }
    for (key, group) in full {
        for b in split_long(merge_boxes(&group)?) {
            pieces.push(Piece { key: key.clone(), centre: box_centre(&b), size: b.size.map(|s| s as f32) });
        }
    }
    Ok(pieces)
}

/// True for a part that is a whole number of grid cells (what disassembly can split back into cells).
fn is_grid_part(p: &Part) -> bool {
    p.size.iter().all(|s| *s >= 1.0 - 1e-3 && (s - s.round()).abs() < 1e-3)
}

fn box_centre(b: &GridBox) -> [f32; 3] {
    std::array::from_fn(|a| b.min[a] as f32 + b.size[a] as f32 * 0.5)
}

/// Rotation matrix of a body that is aligned to the block grid; error otherwise.
fn grid_rotation(iso: &Isometry3<f32>) -> Result<Matrix3<f32>, String> {
    let m: Matrix3<f32> = *iso.rotation.to_rotation_matrix().matrix();
    for r in 0..3 {
        for c in 0..3 {
            let a = m[(r, c)].abs();
            if a > ALIGN_TOLERANCE && (a - 1.0).abs() > ALIGN_TOLERANCE {
                return Err(
                    "body is not grid-aligned (rotation must be a multiple of 90 degrees); \
                     let it settle before attach or disassemble"
                        .into(),
                );
            }
        }
    }
    Ok(m)
}

/// World box (centre, size) -> (local centre offset, local size) for an aligned body.
fn world_box_to_local(centre: [f32; 3], size: [f32; 3], rot: &Matrix3<f32>, origin: &Vector3<f32>) -> ([f32; 3], [f32; 3]) {
    let local = rot.transpose() * (Vector3::from(centre) - origin);
    let local_size = std::array::from_fn(|i| {
        (0..3).map(|j| rot[(j, i)].abs() * size[j]).sum::<f32>()
    });
    (std::array::from_fn(|i| local[i]), local_size)
}

/// Local part -> world box with integer corners; error if it is not on the block grid.
fn local_part_to_world_box(part: &Part, rot: &Matrix3<f32>, origin: &Vector3<f32>) -> Result<GridBox, String> {
    let world_centre = origin + rot * Vector3::from(part.offset);
    let mut min = [0i32; 3];
    let mut size = [0i32; 3];
    for i in 0..3 {
        let ext = (0..3).map(|j| rot[(i, j)].abs() * part.size[j]).sum::<f32>();
        let lo = world_centre[i] - ext * 0.5;
        let s = ext;
        let (lo_r, s_r) = (lo.round(), s.round());
        if (lo - lo_r).abs() > ALIGN_TOLERANCE || (s - s_r).abs() > ALIGN_TOLERANCE || s_r < 1.0 {
            return Err("body parts do not sit on the block grid; cannot disassemble".into());
        }
        min[i] = lo_r as i32;
        size[i] = s_r as i32;
    }
    Ok(GridBox { min, size })
}

/// Snapped variant for a body that rests off-grid by a whole-block-rounded translation.
/// All parts must share one fractional offset per axis (a rigid translation). Otherwise
/// rounding could split the body, so this is refused before anything is removed.
fn snapped_world_box(
    part: &Part,
    rot: &Matrix3<f32>,
    origin: &Vector3<f32>,
    reference: &mut [Option<f32>; 3],
) -> Result<GridBox, String> {
    let world_centre = origin + rot * Vector3::from(part.offset);
    let mut min = [0i32; 3];
    let mut size = [0i32; 3];
    for i in 0..3 {
        let ext = (0..3).map(|j| rot[(i, j)].abs() * part.size[j]).sum::<f32>();
        let s_r = ext.round();
        if (ext - s_r).abs() > ALIGN_TOLERANCE || s_r < 1.0 {
            return Err("body parts do not sit on the block grid; cannot disassemble".into());
        }
        let lo = world_centre[i] - ext * 0.5;
        let frac = lo - lo.floor();
        match reference[i] {
            None => {
                if (frac - 0.5).abs() < SNAP_TOLERANCE {
                    return Err(
                        "body sits exactly half a block off the grid; move it slightly before disassembly"
                            .into(),
                    );
                }
                reference[i] = Some(frac);
            }
            Some(r) => {
                let d = (frac - r).abs();
                if d.min(1.0 - d) > SNAP_TOLERANCE {
                    return Err("body parts are not rigidly translated together; cannot disassemble".into());
                }
            }
        }
        min[i] = lo.round() as i32;
        size[i] = s_r as i32;
    }
    Ok(GridBox { min, size })
}

fn overlaps(a_off: [f32; 3], a_size: [f32; 3], b: &Part) -> bool {
    (0..3).all(|i| {
        (a_off[i] - b.offset[i]).abs() < (a_size[i] + b.size[i]) * 0.5 - 1e-4
    })
}

impl World {
    pub(super) fn assemble_body(&mut self, id: u64, cells: Vec<AssemblyCell>) -> Result<String, String> {
        if id == 0 || id > i64::MAX as u64 {
            return Err("body id must be positive signed 64-bit".into());
        }
        if self.entries.contains_key(&id) {
            return Err(format!("body {id} already exists"));
        }
        let pieces = merged_pieces(&cells)?;
        if pieces.len() > MAX_PARTS_PER_BODY {
            return Err(format!(
                "assembly needs {} colliders; limit is {MAX_PARTS_PER_BODY} per body — split it",
                pieces.len()
            ));
        }
        if pieces.is_empty() {
            return Err("no collidable cells: nothing to build a body from".into());
        }
        // Origin = centroid of the cell centres; offsets are relative to it.
        let n = cells.len() as f64;
        let mut sum = [0f64; 3];
        for c in &cells {
            for (acc, &k) in sum.iter_mut().zip(&c.cell) {
                *acc += k as f64 + 0.5;
            }
        }
        let origin: [f32; 3] = sum.map(|s| (s / n) as f32);
        let parts: Vec<Part> = pieces
            .iter()
            .map(|p| {
                let offset = std::array::from_fn(|a| p.centre[a] - origin[a]);
                part_for(&p.key, p.size, offset)
            })
            .collect();
        let n_parts = parts.len();
        self.add_body(Body {
            id,
            position: origin,
            rotation: identity(),
            velocity: zero(),
            parts,
            wing: None,
        })?;
        Ok(format!("assembled body {id}: {} cells, {n_parts} colliders", cells.len()))
    }

    pub(super) fn attach_cells(&mut self, id: u64, cells: Vec<AssemblyCell>) -> Result<String, String> {
        let (handle, existing) = {
            let entry = self.entries.get(&id).ok_or_else(|| format!("unknown body {id}"))?;
            (entry.handle, entry.spec.parts.clone())
        };
        let iso = *self.bodies[handle].position();
        let rot = grid_rotation(&iso)?;
        let origin = iso.translation.vector;
        let pieces = merged_pieces(&cells)?;
        if existing.len() + pieces.len() > MAX_PARTS_PER_BODY {
            return Err(format!(
                "body {id} would exceed {MAX_PARTS_PER_BODY} colliders; create a new body instead"
            ));
        }
        let total: usize = self.entries.values().map(|e| e.spec.parts.len()).sum();
        if total + pieces.len() > MAX_PARTS_TOTAL {
            return Err(format!("world collider limit {MAX_PARTS_TOTAL} would be exceeded"));
        }
        let new_parts: Vec<Part> = pieces
            .iter()
            .map(|p| {
                let (offset, size) = world_box_to_local(p.centre, p.size, &rot, &origin);
                part_for(&p.key, size, offset)
            })
            .collect();
        for np in &new_parts {
            if existing.iter().any(|ep| overlaps(np.offset, np.size, ep)) {
                return Err("cell overlaps an existing part of the body".into());
            }
        }
        // Validate before mutating Rapier (all parts were already checked by merged_pieces).
        for np in &new_parts {
            np.validate()?;
        }
        for part in &new_parts {
            let c = ColliderBuilder::cuboid(part.size[0] * 0.5, part.size[1] * 0.5, part.size[2] * 0.5)
                .translation(v(part.offset))
                .density(part.density)
                .friction(part.friction)
                .restitution(part.restitution)
                .build();
            self.colliders.insert_with_parent(c, handle, &mut self.bodies);
        }
        let entry = self.entries.get_mut(&id).ok_or("body vanished during attach")?;
        entry.spec.parts.extend(new_parts);
        entry.surfaces = forces::exposed_surfaces(&entry.spec.parts);
        self.bodies[handle].recompute_mass_properties_from_colliders(&self.colliders);
        Ok(format!(
            "attached {} cells to body {id} ({} colliders)",
            cells.len(),
            entry.spec.parts.len()
        ))
    }

    pub(super) fn disassemble_body(&mut self, id: u64, snap: bool) -> Result<(String, Vec<[i32; 3]>), String> {
        let (handle, parts) = {
            let entry = self.entries.get(&id).ok_or_else(|| format!("unknown body {id}"))?;
            (entry.handle, entry.spec.parts.clone())
        };
        let iso = *self.bodies[handle].position();
        let rot = grid_rotation(&iso)?;
        let origin = iso.translation.vector;
        if parts.iter().any(|p| !is_grid_part(p)) {
            // Sub-cell colliders cannot be split back into cells. The caller places its blocks
            // from its own record, shifted by the body pose, so no cell list is returned.
            self.remove_body(id)?;
            return Ok((format!("body {id} disassembled (sub-cell shapes)"), Vec::new()));
        }
        let mut cells = Vec::new();
        let mut reference: [Option<f32>; 3] = [None; 3];
        for part in &parts {
            let b = if snap {
                snapped_world_box(part, &rot, &origin, &mut reference)?
            } else {
                local_part_to_world_box(part, &rot, &origin)?
            };
            if cells.len() + b.volume() as usize > MAX_DISASSEMBLED_CELLS {
                return Err("disassembly would exceed 16384 cells".into());
            }
            cells.extend(b.cells());
        }
        let unique: BTreeSet<[i32; 3]> = cells.iter().copied().collect();
        if unique.len() != cells.len() {
            return Err("internal error: overlapping parts during disassembly".into());
        }
        self.remove_body(id)?;
        Ok((format!("body {id} disassembled into {} cells", cells.len()), cells))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cell(c: [i32; 3], density: f32) -> AssemblyCell {
        AssemblyCell {
            cell: c,
            density,
            friction: 0.6,
            restitution: 0.0,
            block: "minecraft:stone".into(),
            boxes: None,
        }
    }
    fn block_cube(lo: [i32; 3], n: i32, density: f32) -> Vec<AssemblyCell> {
        let mut out = Vec::new();
        for x in 0..n {
            for y in 0..n {
                for z in 0..n {
                    out.push(cell([lo[0] + x, lo[1] + y, lo[2] + z], density));
                }
            }
        }
        out
    }
    fn run(w: &mut World, dt: f32, commands: Vec<Command>) -> Snapshot {
        w.exchange(Request { dt, terrain: None, water_cells: None, actors: None, grabs: None, commands })
            .unwrap()
    }
    fn mass_of(s: &Snapshot, id: u64) -> f32 {
        s.bodies.iter().find(|b| b.id == id).map(|b| b.mass).unwrap()
    }
    fn sorted(mut v: Vec<[i32; 3]>) -> Vec<[i32; 3]> {
        v.sort();
        v
    }
    fn yawed(deg: f32) -> [f32; 4] {
        let h = deg.to_radians() * 0.5;
        [0.0, h.sin(), 0.0, h.cos()]
    }

    #[test]
    fn assembled_cube_is_one_body_with_exact_mass() {
        let mut w = World::new(Config::default()).unwrap();
        let s = run(&mut w, 0.0, vec![Command::AssembleBody { id: 1, cells: block_cube([0, 10, 0], 4, 800.0) }]);
        assert!(s.results[0].ok, "{}", s.results[0].message);
        assert!((mass_of(&s, 1) - 800.0 * 64.0).abs() < 800.0 * 64.0 * 1e-3);
        assert_eq!(s.bodies[0].parts.len(), 1, "solid cube must merge into one collider");
    }

    #[test]
    fn l_shape_has_exact_volume_and_round_trips() {
        let mut cells = block_cube([0, 10, 0], 4, 500.0);
        cells.retain(|c| !(c.cell[0] >= 2 && c.cell[2] >= 2));
        let expected: Vec<[i32; 3]> = sorted(cells.iter().map(|c| c.cell).collect());
        let mut w = World::new(Config::default()).unwrap();
        let s = run(&mut w, 0.0, vec![Command::AssembleBody { id: 2, cells: cells.clone() }]);
        assert!(s.results[0].ok, "{}", s.results[0].message);
        assert!((mass_of(&s, 2) - 500.0 * cells.len() as f32).abs() < 500.0 * cells.len() as f32 * 1e-3);
        let s = run(&mut w, 0.0, vec![Command::DisassembleBody { id: 2, snap: false }]);
        assert!(s.results[0].ok, "{}", s.results[0].message);
        assert_eq!(sorted(s.results[0].cells.clone().unwrap()), expected);
        assert!(s.bodies.is_empty());
    }

    #[test]
    fn attach_adds_mass_and_rejects_overlap() {
        let mut w = World::new(Config::default()).unwrap();
        let s = run(&mut w, 0.0, vec![Command::AssembleBody { id: 3, cells: block_cube([0, 10, 0], 2, 1000.0) }]);
        assert!((mass_of(&s, 3) - 8000.0).abs() < 1.0);
        let s = run(
            &mut w,
            0.0,
            vec![
                Command::AttachCells { id: 3, cells: vec![cell([2, 10, 0], 1000.0)] },
                Command::AttachCells { id: 3, cells: vec![cell([0, 10, 0], 1000.0)] },
            ],
        );
        assert!(s.results[0].ok, "{}", s.results[0].message);
        assert!(!s.results[1].ok, "overlapping cell must be rejected");
        assert!((mass_of(&s, 3) - 9000.0).abs() < 1.0, "failed attach must not change mass");
    }

    #[test]
    fn attach_respects_a_90_degree_body_frame() {
        // Body rotated 90 degrees about Y: a world +X neighbour is a local +/-Z neighbour.
        let mut w = World::new(Config::default()).unwrap();
        let body = Body {
            id: 5,
            position: [0.5, 10.5, 0.5],
            rotation: yawed(90.0),
            velocity: zero(),
            parts: vec![Part {
                offset: zero(),
                size: [1.0; 3],
                density: 1000.0,
                friction: 0.6,
                restitution: 0.0,
                block: "minecraft:stone".into(),
            }],
            wing: None,
        };
        let s = run(
            &mut w,
            0.0,
            vec![
                Command::AddBody { body },
                Command::AttachCells { id: 5, cells: vec![cell([1, 10, 0], 1000.0)] },
                Command::DisassembleBody { id: 5, snap: false },
            ],
        );
        assert!(s.results[1].ok, "{}", s.results[1].message);
        assert!(s.results[2].ok, "{}", s.results[2].message);
        assert_eq!(sorted(s.results[2].cells.clone().unwrap()), vec![[0, 10, 0], [1, 10, 0]]);
    }

    #[test]
    fn tilted_body_is_refused_for_attach_and_disassemble() {
        let mut w = World::new(Config::default()).unwrap();
        let body = Body {
            id: 6,
            position: [0.5, 10.5, 0.5],
            rotation: yawed(45.0),
            velocity: zero(),
            parts: vec![Part {
                offset: zero(),
                size: [1.0; 3],
                density: 1000.0,
                friction: 0.6,
                restitution: 0.0,
                block: "minecraft:stone".into(),
            }],
            wing: None,
        };
        let s = run(
            &mut w,
            0.0,
            vec![
                Command::AddBody { body },
                Command::AttachCells { id: 6, cells: vec![cell([1, 10, 0], 1000.0)] },
                Command::DisassembleBody { id: 6, snap: false },
            ],
        );
        assert!(s.results[1].message.contains("grid-aligned"));
        assert!(!s.results[2].ok);
        assert_eq!(s.bodies.len(), 1, "refused disassembly must keep the body");
    }

    #[test]
    fn checkerboard_exceeding_collider_budget_is_rejected() {
        let mut cells = Vec::new();
        for x in 0..6 {
            for y in 0..6 {
                for z in 0..6 {
                    if (x + y + z) % 2 == 0 {
                        cells.push(cell([x, 10 + y, z], 900.0));
                    }
                }
            }
        }
        let mut w = World::new(Config::default()).unwrap();
        let s = run(&mut w, 0.0, vec![Command::AssembleBody { id: 7, cells }]);
        assert!(!s.results[0].ok);
        assert!(s.results[0].message.contains("colliders"));
        assert!(s.bodies.is_empty());
    }

    #[test]
    fn duplicate_cells_across_materials_are_rejected() {
        let mut w = World::new(Config::default()).unwrap();
        let s = run(
            &mut w,
            0.0,
            vec![Command::AssembleBody {
                id: 8,
                cells: vec![cell([0, 10, 0], 100.0), cell([0, 10, 0], 200.0)],
            }],
        );
        assert!(!s.results[0].ok);
        assert!(s.bodies.is_empty());
    }

    #[test]
    fn assembled_body_falls_under_gravity() {
        let mut w = World::new(Config::default()).unwrap();
        run(&mut w, 0.0, vec![Command::AssembleBody { id: 9, cells: block_cube([0, 30, 0], 2, 600.0) }]);
        let start = run(&mut w, 0.0, vec![]).bodies[0].position[1];
        let mut end = start;
        for _ in 0..10 {
            end = run(&mut w, 0.1, vec![]).bodies[0].position[1];
        }
        assert!(start - end > 3.0, "fell only {} m in 1 s", start - end);
    }

    #[test]
    fn snapped_disassembly_rounds_a_moved_body_to_whole_blocks() {
        let mut w = World::new(Config::default()).unwrap();
        let s = run(&mut w, 0.0, vec![Command::AssembleBody { id: 1, cells: block_cube([0, 0, 0], 2, 1000.0) }]);
        assert!(s.results[0].ok, "{}", s.results[0].message);
        let h = w.entries[&1].handle;
        let moved = Isometry3::translation(0.3, -0.7, 0.2) * *w.bodies[h].position();
        w.bodies[h].set_position(moved, true);
        let s = run(&mut w, 0.0, vec![Command::DisassembleBody { id: 1, snap: true }]);
        assert!(s.results[0].ok, "{}", s.results[0].message);
        let got = sorted(s.results[0].cells.clone().unwrap());
        // Rounding the -0.7 shift gives -1 on y; x (+0.3) and z (+0.2) round to 0.
        let want = sorted(block_cube([0, 0, 0], 2, 1000.0).iter().map(|c| [c.cell[0], c.cell[1] - 1, c.cell[2]]).collect());
        assert_eq!(got, want);
        assert!(!w.entries.contains_key(&1));
    }

    #[test]
    fn snapped_disassembly_refuses_a_body_half_a_block_off_grid() {
        let mut w = World::new(Config::default()).unwrap();
        run(&mut w, 0.0, vec![Command::AssembleBody { id: 1, cells: block_cube([0, 0, 0], 2, 1000.0) }]);
        let h = w.entries[&1].handle;
        let moved = Isometry3::translation(0.5, 0.0, 0.0) * *w.bodies[h].position();
        w.bodies[h].set_position(moved, true);
        let s = run(&mut w, 0.0, vec![Command::DisassembleBody { id: 1, snap: true }]);
        assert!(!s.results[0].ok);
        assert!(w.entries.contains_key(&1), "a refused disassembly must keep the body");
    }

    #[test]
    fn half_height_slab_keeps_its_exact_height() {
        // alpha.7 acceptance: a slab cell (half a block high) must not be inflated to a full cube.
        let mut w = World::new(Config::default()).unwrap();
        let mut slab = cell([0, 0, 0], 2500.0);
        slab.boxes = Some(vec![[0.0, 0.0, 0.0, 1.0, 0.5, 1.0]]);
        let s = run(&mut w, 0.0, vec![Command::AssembleBody { id: 1, cells: vec![slab] }]);
        assert!(s.results[0].ok, "{}", s.results[0].message);
        let parts = &w.entries[&1].spec.parts;
        assert_eq!(parts.len(), 1);
        assert!((parts[0].size[1] - 0.5).abs() < 1e-5, "slab height {}", parts[0].size[1]);
        assert!((parts[0].size[0] - 1.0).abs() < 1e-5 && (parts[0].size[2] - 1.0).abs() < 1e-5);
    }

    #[test]
    fn stair_cell_gets_one_exact_collider_per_shape_box() {
        // Two boxes: a full-width bottom step and a back half-height step on top.
        let mut w = World::new(Config::default()).unwrap();
        let mut stair = cell([0, 0, 0], 2500.0);
        stair.boxes = Some(vec![[0.0, 0.0, 0.0, 1.0, 0.5, 1.0], [0.0, 0.5, 0.0, 1.0, 1.0, 0.5]]);
        let s = run(&mut w, 0.0, vec![Command::AssembleBody { id: 2, cells: vec![stair] }]);
        assert!(s.results[0].ok, "{}", s.results[0].message);
        let parts = &w.entries[&2].spec.parts;
        assert_eq!(parts.len(), 2);
        let volume: f32 = parts.iter().map(|p| p.size[0] * p.size[1] * p.size[2]).sum();
        assert!((volume - 0.75).abs() < 1e-5, "stair volume {volume}");
    }

    #[test]
    fn sub_cell_body_is_refused_for_cell_splitting_but_removed() {
        let mut w = World::new(Config::default()).unwrap();
        let mut slab = cell([0, 0, 0], 2500.0);
        slab.boxes = Some(vec![[0.0, 0.0, 0.0, 1.0, 0.5, 1.0]]);
        run(&mut w, 0.0, vec![Command::AssembleBody { id: 3, cells: vec![slab] }]);
        let (_, cells) = w.disassemble_body(3, true).unwrap();
        assert!(cells.is_empty(), "sub-cell bodies return no cell list");
        assert!(!w.entries.contains_key(&3));
    }
}
