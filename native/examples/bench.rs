//! Stage-0 profiling baseline: release-mode timings for the native kernel.
//! Run: cargo run --release --example bench
use std::time::Instant;
use foxphys_core::{engine::World, protocol::*, water::Tank};

fn cube(id: u64, x: f32, z: f32) -> Body {
    Body {
        id,
        position: [x, 4.0, z],
        rotation: identity(),
        velocity: zero(),
        parts: vec![Part {
            offset: zero(),
            size: [1.0; 3],
            density: 600.0,
            friction: 0.6,
            restitution: 0.0,
            block: "minecraft:oak_planks".into(),
        }],
        wing: None,
    }
}

fn tank(n: usize) -> TankSpec {
    TankSpec {
        origin: zero(),
        nx: n,
        nz: n,
        dx: 0.5,
        depth_left: 2.0,
        depth_right: 0.5,
        viscosity: 0.001,
        damping: 0.02,
        roughness: 0.0,
    }
}

fn block_cube(n: i32, density: f32) -> Vec<AssemblyCell> {
    let mut out = Vec::new();
    for x in 0..n {
        for y in 0..n {
            for z in 0..n {
                out.push(AssemblyCell {
                    cell: [x, 20 + y, z],
                    density,
                    friction: 0.6,
                    restitution: 0.0,
                    block: "minecraft:stone".into(),
                    boxes: None,
                });
            }
        }
    }
    out
}

fn main() {
    // 0) Assembler: converting an 8^3 region, attaching 100 cells, disassembling.
    {
        let mut w = World::new(Config::default()).unwrap();
        let t0 = Instant::now();
        let s = w
            .exchange(Request { dt: 0.0, terrain: None, water_cells: None, actors: None, grabs: None, commands: vec![Command::AssembleBody { id: 1, cells: block_cube(8, 800.0) }] })
            .unwrap();
        let assemble_ms = t0.elapsed().as_secs_f64() * 1e3;
        assert!(s.results[0].ok, "{}", s.results[0].message);
        let extra: Vec<AssemblyCell> = (0..100)
            .map(|i| AssemblyCell { cell: [8, 20 + (i / 10), i % 10], density: 800.0, friction: 0.6, restitution: 0.0, block: "minecraft:stone".into(), boxes: None })
            .collect();
        let t1 = Instant::now();
        let s = w
            .exchange(Request { dt: 0.0, terrain: None, water_cells: None, actors: None, grabs: None, commands: vec![Command::AttachCells { id: 1, cells: extra }] })
            .unwrap();
        let attach_ms = t1.elapsed().as_secs_f64() * 1e3;
        assert!(s.results[0].ok, "{}", s.results[0].message);
        let t2 = Instant::now();
        let s = w
            .exchange(Request { dt: 0.0, terrain: None, water_cells: None, actors: None, grabs: None, commands: vec![Command::DisassembleBody { id: 1, snap: false }] })
            .unwrap();
        let disassemble_ms = t2.elapsed().as_secs_f64() * 1e3;
        assert!(s.results[0].ok, "{}", s.results[0].message);
        println!("assembler: assemble 8^3={assemble_ms:.3} ms  attach 100 cells={attach_ms:.3} ms  disassemble={disassemble_ms:.3} ms  cells_back={}", s.results[0].cells.as_ref().map_or(0, |c| c.len()));
    }

    // 1) Rigid bodies: 10 simulated seconds in 20 Hz exchanges, 120 Hz fixed steps.
    for bodies in [1usize, 16, 64] {
        let mut w = World::new(Config::default()).unwrap();
        let mut commands: Vec<Command> = (0..bodies)
            .map(|i| Command::AddBody {
                body: cube(i as u64 + 1, (i % 8) as f32 * 2.0, (i / 8) as f32 * 2.0),
            })
            .collect();
        commands.push(Command::SetTank { tank: tank(16) });
        let started = Instant::now();
        for _ in 0..200 {
            // The protocol allows at most 64 commands per exchange.
            let n = commands.len().min(60);
            let batch: Vec<Command> = commands.drain(..n).collect();
            let req = Request { dt: 0.05, terrain: None, water_cells: None, actors: None, grabs: None, commands: batch };
            w.exchange(req).unwrap();
        }
        let ms = started.elapsed().as_secs_f64() * 1e3;
        println!("rigid_bodies={bodies:3} sim=10s steps=1200 wall={ms:8.2} ms  per_fixed_step={:.3} ms", ms / 1200.0);
    }

    // 2) Shallow-water tank alone: 1 simulated second, 120 fixed advance calls.
    for n in [16usize, 32] {
        let mut t = Tank::new(tank(n), 9.81).unwrap();
        let started = Instant::now();
        for _ in 0..120 {
            t.advance(1.0 / 120.0).unwrap();
        }
        let ms = started.elapsed().as_secs_f64() * 1e3;
        println!("water {n}x{n}: wall={ms:8.3} ms per 1 sim-second  per_advance={:.4} ms  volume_err={:.2e}", ms / 120.0, t.volume_error());
    }
}
