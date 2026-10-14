//! Deterministic lab demonstration without Minecraft or a JVM.
use foxphys_core::{engine::World, protocol::*};
fn cube(id: u64, x: f32, density: f32) -> Body {
    Body {
        id,
        position: [x, 4.0, 5.0],
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
fn main() {
    let mut w = World::new(Config::default()).expect("configuration");
    let tank = TankSpec {
        origin: zero(),
        nx: 12,
        nz: 12,
        dx: 1.0,
        depth_left: 3.0,
        depth_right: 3.0,
        viscosity: 0.001,
        damping: 0.04,
        roughness: 0.0,
    };
    w.exchange(Request {
        commands: vec![
            Command::SetTank { tank },
            Command::AddBody {
                body: cube(1, 4.0, 550.0),
            },
            Command::AddBody {
                body: cube(2, 8.0, 2600.0),
            },
        ],
        ..Request::default()
    })
    .unwrap();
    for _ in 0..200 {
        w.exchange(Request {
            dt: 0.05,
            ..Request::default()
        })
        .unwrap();
    }
    let s = w.snapshot(vec![]);
    println!(
        "logical_seconds={:.2}, fixed_steps={}",
        s.sim_seconds, s.fixed_steps
    );
    for b in s.bodies {
        println!(
            "body={} mass_kg={:.2} center_y_m={:.5} vertical_velocity_m_s={:.5}",
            b.id, b.mass, b.position[1], b.velocity[1]
        );
    }
    let t = s.tank.unwrap();
    println!(
        "water_volume_m3={:.9} relative_volume_error={:.3e}",
        t.volume, t.relative_volume_error
    );
}
