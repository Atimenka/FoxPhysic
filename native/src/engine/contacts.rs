//! Actor contacts and gravity-gun grabs.
//!
//! Actors (players, living entities) are boxes placed by Java every tick. After each fixed step
//! their penetration into dynamic bodies is measured with the exact shape-shape contact query.
//! The push-out is returned to Java, which moves the entity; the body receives the matching
//! impulse (position-based exchange, no penalty force). Terrain stays with the vanilla world.
//!
//! Grabs hold one body point at a target with a critically damped spring whose gains scale with
//! the body mass, so the same grab feels the same on a 40 kg and a 2600 kg body.
use super::World;
use crate::{
    forces::v,
    protocol::{ActorBox, ActorContact},
};
use rapier3d::{
    na::{Isometry3, Point3, Translation3, Vector3},
    parry::{query, shape::Cuboid},
    prelude::*,
};

/// Largest correction applied to one actor in one step (metres). Larger means a broken geometry.
const MAX_CORRECTION: f32 = 0.5;
/// Largest body impulse from one actor contact per step, in m/s of velocity change at the body.
const MAX_BODY_DELTA_V: f32 = 10.0;
/// Speed cap of the depth term of one contact (m/s) and its share of depth/dt.
const MAX_PUSH_SPEED: f32 = 2.0;
const PUSH_GAIN: f32 = 0.1;
/// A floor contact points down into the body by at least this cosine. Edges give diagonal normals,
/// so this is low; the contact point must also lie in the lower half of the actor (its feet).
const FLOOR_COS: f32 = 0.2;

impl World {
    /// Applies the spring-damper of every grab. Called once per fixed step before the pipeline.
    pub(super) fn apply_grabs(&mut self) {
        for g in &self.grabs {
            let Some(entry) = self.entries.get(&g.id) else {
                continue;
            };
            let rb = &mut self.bodies[entry.handle];
            let mass = rb.mass();
            let omega = g.frequency;
            let stiffness = mass * omega * omega;
            let damping = 2.0 * g.damping_ratio * mass * omega;
            let point = rb.position() * Point3::from(v(g.point));
            let velocity = rb.velocity_at_point(&point);
            let mut force = (v(g.target) - point.coords) * stiffness - velocity * damping;
            let limit = g.max_accel * mass;
            let norm = force.norm();
            if norm.is_finite() && norm > limit && limit > 0.0 {
                force *= limit / norm;
            }
            if force.iter().all(|x| x.is_finite()) {
                rb.add_force_at_point(force, point, true);
            }
        }
    }

    /// Computes contacts between actor boxes and body colliders in the current poses.
    pub(super) fn compute_contacts(&mut self) {
        self.contacts.clear();
        self.contact_step = self.fixed_steps;
        if self.actors.is_empty() || self.entries.is_empty() {
            return;
        }
        let dt = self.integration.dt;
        let mut kicks: Vec<(RigidBodyHandle, Vector3<f32>, Point3<f32>)> = Vec::new();
        let actors = self.actors.clone();
        for actor in &actors {
            let (correction, carry, standing) = self.actor_contacts(actor, dt, &mut kicks);
            self.contacts.push(ActorContact {
                actor: actor.key,
                correction: [correction.x, correction.y, correction.z],
                carry: [carry.x, carry.y, carry.z],
                standing,
            });
        }
        for (handle, impulse, point) in kicks {
            self.bodies[handle].apply_impulse_at_point(impulse, point, true);
        }
    }

    /// Returns (correction, mean carry velocity, standing) for one actor and queues body kicks.
    fn actor_contacts(
        &self,
        actor: &ActorBox,
        dt: f32,
        kicks: &mut Vec<(RigidBodyHandle, Vector3<f32>, Point3<f32>)>,
    ) -> (Vector3<f32>, Vector3<f32>, bool) {
        let centre = Vector3::from(v(actor.center));
        let half = v(actor.half);
        let pose = Isometry3::from_parts(Translation3::from(centre), Default::default());
        let shape = Cuboid::new(half);
        // Broad phase: the actor's box grown by a small margin.
        let lo = centre - half - Vector3::repeat(0.05);
        let hi = centre + half + Vector3::repeat(0.05);
        let mut correction = Vector3::zeros();
        let mut carry = Vector3::zeros();
        let mut carried = 0usize;
        let mut standing = false;
        for entry in self.entries.values() {
            let rb = &self.bodies[entry.handle];
            for &ch in rb.colliders() {
                let Some(collider) = self.colliders.get(ch) else {
                    continue;
                };
                let aabb = collider.compute_aabb();
                let (blo, bhi) = (aabb.mins.coords, aabb.maxs.coords);
                if (0..3).any(|i| bhi[i] < lo[i] || blo[i] > hi[i]) {
                    continue;
                }
                let contact = match query::contact(&pose, &shape, collider.position(), collider.shape(), 0.0) {
                    Ok(Some(c)) if c.dist < 0.0 => c,
                    _ => continue,
                };
                let depth = -contact.dist;
                // normal1 points out of the actor, into the body.
                let normal: Vector3<f32> = contact.normal1.into_inner();
                let body_point = contact.point2;
                let floor = normal.y < -FLOOR_COS && body_point.y <= centre.y - half.y * 0.5;
                if floor {
                    // Feet on a surface: lift only. A sideways part would push the actor off an edge.
                    correction.y += depth * (-normal.y);
                    standing = true;
                    carry += rb.velocity_at_point(&body_point);
                    carried += 1;
                } else {
                    correction -= normal * depth;
                }
                // Position-based exchange. A body above the actor presses the actor into the floor, so
                // the actor is pinned (infinite mass for the exchange): the body takes the whole
                // closing velocity and the overlap in one step. A body below the actor lifts it, and the
                // exchange uses the reduced mass, so a light actor is moved by a heavy body.
                let body_mass = rb.mass().max(1e-3);
                let exchange_mass = if normal.y > 0.0 {
                    body_mass
                } else {
                    let m = actor.mass.max(1e-3);
                    m * body_mass / (m + body_mass)
                };
                // Speed at which the body approaches the actor along the normal (0 when separating).
                let closing = (-rb.velocity_at_point(&body_point).dot(&normal)).max(0.0);
                // The actor moves once per server tick (about 6 fixed steps), so an overlap can last
                // several steps. A full depth/dt term would add speed on every one of them and run
                // away; a small capped term only holds the body out. The position is fixed by Java.
                let push = ((depth / dt).min(MAX_PUSH_SPEED)) * PUSH_GAIN;
                let mut kick = normal * (exchange_mass * (closing + push));
                let norm = kick.norm();
                let cap = MAX_BODY_DELTA_V * body_mass;
                if norm > cap {
                    kick *= cap / norm;
                }
                if kick.iter().all(|x| x.is_finite()) {
                    kicks.push((entry.handle, kick, body_point));
                }
            }
        }
        let norm = correction.norm();
        if norm > MAX_CORRECTION {
            correction *= MAX_CORRECTION / norm;
        }
        if carried > 0 {
            carry /= carried as f32;
        }
        (correction, carry, standing)
    }
}

#[cfg(test)]
mod tests {
    use crate::{
        engine::World,
        protocol::{ActorBox, Body, Command, Config, Grab, Part, Request},
    };

    fn block_body(id: u64, pos: [f32; 3], half: f32, mass_density: f32) -> Body {
        Body {
            id,
            position: pos,
            rotation: [0.0, 0.0, 0.0, 1.0],
            velocity: [0.0; 3],
            parts: vec![Part {
                offset: [0.0; 3],
                size: [half * 2.0; 3],
                density: mass_density,
                friction: 0.6,
                restitution: 0.0,
                block: "minecraft:stone".into(),
            }],
            wing: None,
        }
    }

    fn world() -> World {
        World::new(Config::default()).unwrap()
    }

    fn add(world: &mut World, body: Body) {
        world
            .exchange(Request {
                commands: vec![Command::AddBody { body }],
                ..Request::default()
            })
            .unwrap();
    }

    fn actor(key: u64, center: [f32; 3]) -> ActorBox {
        ActorBox {
            key,
            center,
            half: [0.3, 0.9, 0.3],
            mass: 80.0,
        }
    }

    #[test]
    fn actor_penetrating_a_body_from_above_is_pushed_up_and_stands() {
        let mut w = world();
        // 1 m cube, density 2600 -> ~2600 kg, top face at y = 1.0.
        add(&mut w, block_body(1, [0.0, 0.5, 0.0], 0.5, 2600.0));
        // Actor feet 5 cm inside the top face.
        let feet_y = 1.0 - 0.05;
        let a = actor(7, [0.0, feet_y + 0.9, 0.0]);
        let snap = w
            .exchange(Request {
                actors: Some(vec![a]),
                ..Request::default()
            })
            .unwrap();
        assert_eq!(snap.contacts.len(), 0, "contacts appear after the first fixed step");
        let snap = w
            .exchange(Request {
                dt: 1.0 / 120.0,
                ..Request::default()
            })
            .unwrap();
        let c = snap.contacts.iter().find(|c| c.actor == 7).expect("contact for actor 7");
        assert!(c.standing, "actor on top of the body must count as standing");
        assert!(c.correction[1] > 0.04 && c.correction[1] < 0.06, "push-out ~5 cm up: {:?}", c.correction);
        assert!(c.correction[0].abs() < 1e-3 && c.correction[2].abs() < 1e-3);
    }

    #[test]
    fn feet_on_a_block_edge_are_lifted_without_sideways_push() {
        let mut w = world();
        // 1 m cube, top face at y = 1.0, x in [-0.5, 0.5].
        add(&mut w, block_body(1, [0.0, 0.5, 0.0], 0.5, 2600.0));
        // Actor overlaps the edge by 2 cm sideways and its feet are 2 cm into the top face,
        // so the contact normal is diagonal.
        let a = actor(9, [0.78, 1.88, 0.0]);
        w.exchange(Request { actors: Some(vec![a]), ..Request::default() }).unwrap();
        let snap = w.exchange(Request { dt: 1.0 / 120.0, ..Request::default() }).unwrap();
        let c = snap.contacts.iter().find(|c| c.actor == 9).expect("contact for actor 9");
        assert!(c.standing, "feet on the edge count as standing");
        assert!(c.correction[0].abs() < 1e-6, "no sideways push on an edge: {:?}", c.correction);
        assert!(c.correction[1] > 0.0, "lifted: {:?}", c.correction);
    }

    #[test]
    fn side_contact_is_not_standing() {
        let mut w = world();
        add(&mut w, block_body(1, [0.0, 0.5, 0.0], 0.5, 2600.0));
        // Actor feet well below the top: it touches the cube from the side only.
        let a = actor(10, [0.78, 0.9, 0.0]);
        w.exchange(Request { actors: Some(vec![a]), ..Request::default() }).unwrap();
        let snap = w.exchange(Request { dt: 1.0 / 120.0, ..Request::default() }).unwrap();
        let c = snap.contacts.iter().find(|c| c.actor == 10).expect("contact for actor 10");
        assert!(!c.standing, "a side contact must not count as standing");
        assert!((c.correction[0] - 0.02).abs() < 1e-3 && c.correction[1].abs() < 1e-6, "pushed out sideways by the 2 cm overlap: {:?}", c.correction);
    }

    #[test]
    fn actor_pushes_a_light_body_away_sideways() {
        let mut w = world();
        // Light body and an actor overlapping sideways by 0.2 m; the actor sits on the +x side.
        add(&mut w, block_body(1, [0.0, 0.5, 0.0], 0.5, 50.0));
        let a = actor(9, [0.6, 0.5, 0.0]); // half-overlap of 0.2 m in x
        w.exchange(Request {
            actors: Some(vec![a]),
            ..Request::default()
        })
        .unwrap();
        let snap = w.exchange(Request { dt: 1.0 / 120.0, ..Request::default() }).unwrap();
        let c = snap.contacts.iter().find(|c| c.actor == 9).unwrap();
        assert!(c.correction[0] > 0.1, "actor moves away from the body (+x): {:?}", c.correction);
        assert!(!c.standing);
        let body = &snap.bodies[0];
        assert!(body.velocity[0] < 0.0, "body receives a kick in -x: {:?}", body.velocity);
    }

    #[test]
    fn standing_actor_stays_on_body_for_seconds() {
        // The actor falls under gravity every step; the caller (Java) applies the push-out
        // reported by the snapshot. After 3 s the actor must still stand on the body, and
        // the body must not have gained a runaway velocity.
        let mut w = world();
        add(&mut w, block_body(1, [0.0, 0.5, 0.0], 0.5, 2600.0));
        // Static floor under the body (terrain box: top face at y = 0).
        w.exchange(Request {
            terrain: Some(vec![crate::protocol::TerrainBox { center: [0.0, -0.5, 0.0], size: [20.0, 1.0, 20.0] }]),
            ..Request::default()
        })
        .unwrap();
        let dt = 1.0 / 120.0;
        let mut feet = 1.0_f32;
        let mut fall_speed = 0.0_f32;
        let mut snap = w.exchange(Request::default()).unwrap();
        for _ in 0..360 {
            fall_speed += 9.81 * dt;
            feet -= fall_speed * dt;
            let centre = [0.0, feet + 0.9, 0.0];
            w.exchange(Request { actors: Some(vec![actor(5, centre)]), ..Request::default() }).unwrap();
            snap = w.exchange(Request { dt, ..Request::default() }).unwrap();
            if let Some(c) = snap.contacts.iter().find(|c| c.actor == 5) {
                feet += c.correction[1];
                if c.correction[1] > 0.0 {
                    fall_speed = 0.0;
                }
            }
        }
        // The body rests on the floor (top at y=0, body centre 0.5), so the actor's feet stay at y=1.
        assert!(feet > 0.9, "actor sank through the body: feet at y={feet}");
        assert!(feet < 1.2, "actor flew away: feet at y={feet}");
        assert!(snap.bodies[0].position[1] > 0.4, "body sank into the floor: {:?}", snap.bodies[0].position);
        let body = &snap.bodies[0];
        assert!(body.velocity.iter().all(|v| v.abs() < 3.0), "body velocity bounded: {:?}", body.velocity);
    }

    #[test]
    fn body_falling_onto_a_standing_actor_is_stopped_by_it() {
        // Player-sized actor (0.6 x 1.8 x 0.6) standing on a floor (top at y = 0). A 1 m body
        // falls from 2 m above the head. The body must not sink deeper than 0.2 m into the actor.
        let mut w = world();
        w.exchange(Request {
            terrain: Some(vec![crate::protocol::TerrainBox { center: [0.0, -0.5, 0.0], size: [20.0, 1.0, 20.0] }]),
            ..Request::default()
        })
        .unwrap();
        add(&mut w, block_body(1, [0.0, 3.5, 0.0], 0.5, 2600.0));
        let dt = 1.0 / 120.0;
        let mut worst = 0.0_f32;
        let mut saw_contact = false;
        for _ in 0..240 {
            // Actor centre at y = 0.9 (feet on the floor).
            w.exchange(Request { actors: Some(vec![actor(7, [0.0, 0.9, 0.0])]), ..Request::default() }).unwrap();
            let snap = w.exchange(Request { dt, ..Request::default() }).unwrap();
            let body_bottom = snap.bodies[0].position[1] - 0.5;
            // Penetration of the body's bottom into the actor's top face (y = 1.8).
            worst = worst.max(1.8 - body_bottom);
            saw_contact |= snap.contacts.iter().any(|c| c.actor == 7 && c.correction[1] < 0.0);
        }
        assert!(saw_contact, "the body never touched the actor");
        assert!(worst < 0.2, "body sank {worst} m into the actor");
    }

    #[test]
    fn overlapping_actor_does_not_accelerate_a_heavy_body() {
        // A player-sized actor overlaps a resting 2600 kg body by 0.3 m from the side. The actor
        // box is refreshed only every 6 steps (once per server tick), as in the game. The body must
        // not gain speed from the overlap that persists between refreshes.
        let mut w = world();
        w.exchange(Request {
            terrain: Some(vec![crate::protocol::TerrainBox { center: [0.0, -0.5, 0.0], size: [20.0, 1.0, 20.0] }]),
            ..Request::default()
        })
        .unwrap();
        add(&mut w, block_body(1, [0.0, 0.5, 0.0], 0.5, 2600.0));
        let dt = 1.0 / 120.0;
        let mut max_speed = 0.0_f32;
        for step in 0..480 {
            // Actor centre at x = 0.7: 0.3 m into the body. Refreshed on every 6th step.
            let actors = if step % 6 == 0 { Some(vec![actor(9, [0.7, 0.9, 0.0])]) } else { None };
            let snap = w.exchange(Request { actors, dt, ..Request::default() }).unwrap();
            let v = snap.bodies[0].velocity;
            max_speed = max_speed.max((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt());
        }
        assert!(max_speed < 0.5, "a resting body was accelerated to {max_speed} m/s by an overlap");
    }

    #[test]
    fn actor_far_away_has_no_contact() {
        let mut w = world();
        add(&mut w, block_body(1, [0.0, 0.5, 0.0], 0.5, 2600.0));
        w.exchange(Request {
            actors: Some(vec![actor(3, [20.0, 2.0, 0.0])]),
            ..Request::default()
        })
        .unwrap();
        let snap = w.exchange(Request { dt: 1.0 / 120.0, ..Request::default() }).unwrap();
        assert!(snap.contacts.iter().all(|c| c.correction == [0.0; 3]));
    }

    #[test]
    fn grab_pulls_a_body_towards_its_target() {
        let mut w = world();
        add(&mut w, block_body(1, [0.0, 10.0, 0.0], 0.5, 2600.0));
        let start = w.exchange(Request::default()).unwrap().bodies[0].position;
        let target = [start[0] + 3.0, start[1], start[2]];
        let grab = Grab {
            id: 1,
            point: [0.0; 3],
            target,
            frequency: 6.0,
            damping_ratio: 0.7,
            max_accel: 60.0,
        };
        let mut snap = w.exchange(Request { grabs: Some(vec![grab]), ..Request::default() }).unwrap();
        for _ in 0..240 {
            snap = w.exchange(Request { dt: 1.0 / 120.0, ..Request::default() }).unwrap();
        }
        let p = snap.bodies[0].position;
        assert!((p[0] - target[0]).abs() < 0.3, "grab reached the target in x: {p:?}");
        assert!(p[0] > start[0] + 2.0);
    }

    #[test]
    fn grab_force_is_capped_by_max_accel() {
        let mut w = world();
        add(&mut w, block_body(1, [0.0, 10.0, 0.0], 0.5, 2600.0));
        let grab = Grab {
            id: 1,
            point: [0.0; 3],
            target: [200.0, 10.0, 0.0],
            frequency: 6.0,
            damping_ratio: 0.0,
            max_accel: 2.0,
        };
        w.exchange(Request { grabs: Some(vec![grab]), ..Request::default() }).unwrap();
        let snap = w.exchange(Request { dt: 1.0 / 120.0, ..Request::default() }).unwrap();
        // One second of 2 m/s^2 would move at most 1 m; a single step is far below that.
        let mass = 2600.0_f32 * 1.0; // unit cube
        assert!(snap.bodies[0].velocity[0].abs() < 0.05, "{:?} mass {mass}", snap.bodies[0].velocity);
    }

    #[test]
    fn actor_limits_are_enforced() {
        let mut w = world();
        let too_many: Vec<ActorBox> = (1..=65).map(|k| actor(k, [0.0; 3])).collect();
        assert!(w.exchange(Request { actors: Some(too_many), ..Request::default() }).is_err());
        assert!(w.exchange(Request { actors: Some(vec![actor(0, [0.0; 3])]), ..Request::default() }).is_err());
        assert!(w
            .exchange(Request { actors: Some(vec![actor(4, [0.0; 3]), actor(4, [1.0; 3])]), ..Request::default() })
            .is_err());
    }
}
