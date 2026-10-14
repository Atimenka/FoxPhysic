//! Pure grid geometry for the physics assembler (no Rapier, no JNI).
//!
//! A block cell with integer coordinate `c` occupies the cube `[c, c+1]^3`.
//! `merge_boxes` covers a set of unique cells with axis-aligned boxes using
//! greedy meshing: the cover is EXACT (no overlap, no gaps, no added volume).
use std::collections::BTreeSet;

/// Largest box side accepted by the physics protocol (`Part::validate`).
pub const MAX_BOX_SIDE: i32 = 16;
/// Bounding-box volume limit for one merge (64^3 cells).
pub const MAX_BBOX_VOLUME: usize = 262_144;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GridBox {
    pub min: [i32; 3],
    pub size: [i32; 3],
}

impl GridBox {
    pub fn volume(&self) -> i64 {
        self.size.iter().map(|&s| s as i64).product()
    }
    /// Iterate integer cells of the box in x-fastest order.
    pub fn cells(&self) -> impl Iterator<Item = [i32; 3]> + '_ {
        let (min, size) = (self.min, self.size);
        (0..size[2]).flat_map(move |z| {
            (0..size[1]).flat_map(move |y| {
                (0..size[0]).map(move |x| [min[0] + x, min[1] + y, min[2] + z])
            })
        })
    }
}

/// Greedy meshing of unique integer cells into boxes that exactly cover them.
pub fn merge_boxes(cells: &[[i32; 3]]) -> Result<Vec<GridBox>, String> {
    if cells.is_empty() {
        return Ok(Vec::new());
    }
    let mut lo = [i32::MAX; 3];
    let mut hi = [i32::MIN; 3];
    for c in cells {
        for a in 0..3 {
            lo[a] = lo[a].min(c[a]);
            hi[a] = hi[a].max(c[a]);
        }
    }
    let dims = [
        (hi[0] - lo[0] + 1) as usize,
        (hi[1] - lo[1] + 1) as usize,
        (hi[2] - lo[2] + 1) as usize,
    ];
    let volume = dims[0]
        .checked_mul(dims[1])
        .and_then(|v| v.checked_mul(dims[2]))
        .filter(|&v| v <= MAX_BBOX_VOLUME)
        .ok_or("assembly bounding box exceeds 64x64x64; split it into smaller bodies")?;
    let index = |x: usize, y: usize, z: usize| (z * dims[1] + y) * dims[0] + x;

    let mut filled = vec![false; volume];
    for c in cells {
        let i = index(
            (c[0] - lo[0]) as usize,
            (c[1] - lo[1]) as usize,
            (c[2] - lo[2]) as usize,
        );
        if filled[i] {
            return Err("duplicate cell in assembly".into());
        }
        filled[i] = true;
    }

    let mut used = vec![false; volume];
    let mut out = Vec::new();
    for z in 0..dims[2] {
        for y in 0..dims[1] {
            for x in 0..dims[0] {
                let start = index(x, y, z);
                if !filled[start] || used[start] {
                    continue;
                }
                // Free means filled and not yet covered by an earlier box.
                let free = |x: usize, y: usize, z: usize| {
                    let i = index(x, y, z);
                    filled[i] && !used[i]
                };
                let mut wx = 1;
                while x + wx < dims[0] && free(x + wx, y, z) {
                    wx += 1;
                }
                let mut wy = 1;
                while y + wy < dims[1] && (0..wx).all(|k| free(x + k, y + wy, z)) {
                    wy += 1;
                }
                let mut wz = 1;
                while z + wz < dims[2]
                    && (0..wy).all(|j| (0..wx).all(|k| free(x + k, y + j, z + wz)))
                {
                    wz += 1;
                }
                for dz in 0..wz {
                    for dy in 0..wy {
                        for dx in 0..wx {
                            used[index(x + dx, y + dy, z + dz)] = true;
                        }
                    }
                }
                out.push(GridBox {
                    min: [lo[0] + x as i32, lo[1] + y as i32, lo[2] + z as i32],
                    size: [wx as i32, wy as i32, wz as i32],
                });
            }
        }
    }
    Ok(out)
}

/// Split boxes whose sides exceed the protocol limit. Splitting keeps the cover exact.
pub fn split_long(boxes: Vec<GridBox>) -> Vec<GridBox> {
    let mut out = Vec::with_capacity(boxes.len());
    for b in boxes {
        let mut stack = vec![b];
        while let Some(c) = stack.pop() {
            match (0..3).find(|&a| c.size[a] > MAX_BOX_SIDE) {
                None => out.push(c),
                Some(a) => {
                    let mut first = c;
                    first.size[a] = MAX_BOX_SIDE;
                    let mut rest = c;
                    rest.min[a] += MAX_BOX_SIDE;
                    rest.size[a] = c.size[a] - MAX_BOX_SIDE;
                    stack.push(rest);
                    stack.push(first);
                }
            }
        }
    }
    out
}

/// Sanity check used by tests: boxes must be disjoint and their union must equal `cells`.
pub fn is_exact_cover(boxes: &[GridBox], cells: &[[i32; 3]]) -> bool {
    let mut seen = BTreeSet::new();
    for b in boxes {
        for c in b.cells() {
            if !seen.insert(c) {
                return false;
            }
        }
    }
    let expected: BTreeSet<[i32; 3]> = cells.iter().copied().collect();
    seen == expected
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(lo: [i32; 3], size: [i32; 3]) -> Vec<[i32; 3]> {
        GridBox { min: lo, size }.cells().collect()
    }
    /// Small deterministic LCG so the random-shape tests are reproducible.
    fn lcg(seed: &mut u64) -> u64 {
        *seed = seed
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        *seed >> 33
    }

    #[test]
    fn solid_cube_is_one_box() {
        let cells = solid([3, -2, 5], [8, 8, 8]);
        let boxes = merge_boxes(&cells).unwrap();
        assert_eq!(boxes.len(), 1);
        assert_eq!(boxes[0].size, [8, 8, 8]);
        assert!(is_exact_cover(&boxes, &cells));
    }

    #[test]
    fn checkerboard_gives_one_box_per_cell() {
        let mut cells = Vec::new();
        for x in 0..4 {
            for y in 0..4 {
                for z in 0..4 {
                    if (x + y + z) % 2 == 0 {
                        cells.push([x, y, z]);
                    }
                }
            }
        }
        let boxes = merge_boxes(&cells).unwrap();
        assert_eq!(boxes.len(), cells.len());
        assert!(is_exact_cover(&boxes, &cells));
    }

    #[test]
    fn random_shapes_are_exact_covers() {
        let mut seed = 0x5eed_u64;
        for _ in 0..200 {
            let mut cells = Vec::new();
            for x in 0..6 {
                for y in 0..6 {
                    for z in 0..6 {
                        if lcg(&mut seed) % 3 != 0 {
                            cells.push([x - 3, y, z + 7]);
                        }
                    }
                }
            }
            if cells.is_empty() {
                continue;
            }
            let boxes = merge_boxes(&cells).unwrap();
            assert!(is_exact_cover(&boxes, &cells));
            assert!(boxes.len() <= cells.len());
        }
    }

    #[test]
    fn duplicate_and_oversized_inputs_are_rejected() {
        assert!(merge_boxes(&[[0, 0, 0], [0, 0, 0]]).is_err());
        assert!(merge_boxes(&[[0, 0, 0], [100, 100, 100]]).is_err());
    }

    #[test]
    fn long_boxes_are_split_into_protocol_sized_pieces() {
        let line = GridBox {
            min: [0, 0, 0],
            size: [20, 1, 1],
        };
        let parts = split_long(vec![line]);
        assert_eq!(parts.len(), 2);
        assert_eq!(parts.iter().map(|b| b.volume()).sum::<i64>(), 20);
        assert!(parts.iter().all(|b| b.size.iter().all(|&s| s <= MAX_BOX_SIDE)));
    }
}
