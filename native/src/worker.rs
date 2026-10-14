//! Physics worker: steps one [`World`] on its own thread.
//!
//! * Inputs (commands, terrain, water cells) arrive through an MPSC channel and
//!   are applied on the worker thread between steps. They never block the caller.
//! * Snapshots are published into a two-slot buffer. The writer fills the back
//!   slot and then flips the index, so readers always see a complete snapshot.
//! * Simulation time follows the wall clock. When the worker falls behind, at
//!   most [`MAX_CATCH_UP_STEPS`] fixed steps run per loop while active, so the
//!   simulation slows down instead of spiralling. While idle the loop runs at
//!   [`IDLE_HZ`] and may integrate up to 0.1 s per loop.
use crate::{
    engine::World,
    protocol::{Config, Request, Snapshot},
};
use std::{
    sync::{
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
        mpsc, Arc, Mutex, RwLock,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

/// Errors the worker could not return to a caller.
#[derive(Default)]
struct Errors {
    /// A fixed step failed; the world is paused and the lab must be treated as failed.
    fatal: Option<String>,
    /// An input was rejected (for example invalid terrain). The world keeps running.
    rejected: Option<String>,
}

pub const MAX_CATCH_UP_STEPS: u32 = 4;
pub const IDLE_HZ: u64 = 10;
/// Largest `dt` accepted by [`World::exchange`].
const MAX_DT: f64 = 0.1;

/// Time the worker integrates in one loop. Pure function, so it can be tested.
pub fn plan_dt(elapsed: Duration, fixed_dt: f64, idle: bool) -> f32 {
    let cap = if idle {
        MAX_DT
    } else {
        (MAX_CATCH_UP_STEPS as f64 * fixed_dt).min(MAX_DT)
    };
    elapsed.as_secs_f64().min(cap) as f32
}

enum Message {
    /// Applied immediately (dt = 0); the result is sent back on `reply`.
    Input {
        request: Request,
        reply: mpsc::Sender<Result<Snapshot, String>>,
    },
    Shutdown,
}

struct Shared {
    slots: [RwLock<Option<Arc<Snapshot>>>; 2],
    front: AtomicUsize,
    published: AtomicU64,
    idle: AtomicBool,
    errors: Mutex<Errors>,
}

impl Shared {
    fn publish(&self, snapshot: Snapshot) {
        let back = 1 - self.front.load(Ordering::Acquire);
        let mut slot = self.slots[back]
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *slot = Some(Arc::new(snapshot));
        drop(slot);
        self.front.store(back, Ordering::Release);
        self.published.fetch_add(1, Ordering::Release);
    }
    fn errors(&self) -> std::sync::MutexGuard<'_, Errors> {
        self.errors.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
    fn latest(&self) -> Option<Arc<Snapshot>> {
        let front = self.front.load(Ordering::Acquire);
        self.slots[front]
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }
}

pub struct PhysicsWorker {
    tx: mpsc::Sender<Message>,
    shared: Arc<Shared>,
    // Mutex makes the worker Sync, so the JNI registry can share it.
    handle: Mutex<Option<JoinHandle<()>>>,
}

impl PhysicsWorker {
    pub fn spawn(config: Config) -> Result<Self, String> {
        let mut world = World::new(config.clone())?;
        let shared = Arc::new(Shared {
            slots: [RwLock::new(None), RwLock::new(None)],
            front: AtomicUsize::new(0),
            published: AtomicU64::new(0),
            idle: AtomicBool::new(false),
            errors: Mutex::new(Errors::default()),
        });
        let (tx, rx) = mpsc::channel::<Message>();
        let worker_shared = Arc::clone(&shared);
        let fixed_dt = 1.0 / config.step_hz as f64;
        let handle = thread::Builder::new()
            .name("foxphys-physics".into())
            .spawn(move || {
                let mut last = Instant::now();
                loop {
                    // 1. Apply queued inputs without advancing time.
                    loop {
                        match rx.try_recv() {
                            Ok(Message::Input { request, reply }) => {
                                let mut request = request;
                                request.dt = 0.0;
                                let result = world.exchange(request);
                                if let Err(e) = &result {
                                    worker_shared.errors().rejected = Some(e.clone());
                                }
                                let _ = reply.send(result);
                            }
                            Ok(Message::Shutdown) | Err(mpsc::TryRecvError::Disconnected) => {
                                return;
                            }
                            Err(mpsc::TryRecvError::Empty) => break,
                        }
                    }
                    // 2. Advance simulation time by the wall-clock elapsed since last loop.
                    let idle = worker_shared.idle.load(Ordering::Acquire);
                    let now = Instant::now();
                    let elapsed = now - last;
                    last = now;
                    let dt = plan_dt(elapsed, fixed_dt, idle);
                    // A failing step pauses the world inside `exchange`; the worker keeps
                    // running so that later inputs can still be inspected or cleared.
                    match world.exchange(Request {
                        dt,
                        ..Request::default()
                    }) {
                        Ok(snapshot) => worker_shared.publish(snapshot),
                        Err(e) => worker_shared.errors().fatal = Some(e),
                    }
                    // 3. Sleep until the next loop.
                    let target = if idle {
                        Duration::from_millis(1000 / IDLE_HZ)
                    } else {
                        Duration::from_secs_f64(fixed_dt)
                    };
                    let spent = Instant::now() - now;
                    if target > spent {
                        thread::sleep(target - spent);
                    }
                }
            })
            .map_err(|e| format!("cannot start physics thread: {e}"))?;
        Ok(Self {
            tx,
            shared,
            handle: Mutex::new(Some(handle)),
        })
    }

    /// Queues a request. The returned receiver yields the result once the worker has applied it.
    pub fn submit(&self, request: Request) -> mpsc::Receiver<Result<Snapshot, String>> {
        let (reply, rx) = mpsc::channel();
        // If the worker has stopped, the receiver reports disconnection.
        let _ = self.tx.send(Message::Input { request, reply });
        rx
    }

    /// Queues a request and waits up to `timeout` for its result. Used for commands and exports.
    pub fn call(&self, request: Request, timeout: Duration) -> Result<Snapshot, String> {
        self.submit(request)
            .recv_timeout(timeout)
            .map_err(|e| match e {
                mpsc::RecvTimeoutError::Timeout => {
                    "physics worker did not answer in time".to_string()
                }
                mpsc::RecvTimeoutError::Disconnected => "physics worker stopped".to_string(),
            })?
    }

    /// Returns and clears one unreported error, prefixed `F:` (fatal) or `R:` (rejected input).
    pub fn take_error(&self) -> Option<String> {
        let mut errors = self.shared.errors();
        if let Some(e) = errors.fatal.take() {
            return Some(format!("F:{e}"));
        }
        errors.rejected.take().map(|e| format!("R:{e}"))
    }

    /// Most recently published snapshot. Never blocks on the physics step.
    pub fn latest(&self) -> Option<Arc<Snapshot>> {
        self.shared.latest()
    }

    pub fn published_count(&self) -> u64 {
        self.shared.published.load(Ordering::Acquire)
    }

    /// Lowers the loop rate when no players are present. The simulation keeps running.
    pub fn set_idle(&self, idle: bool) {
        self.shared.idle.store(idle, Ordering::Release);
    }
}

impl Drop for PhysicsWorker {
    fn drop(&mut self) {
        let _ = self.tx.send(Message::Shutdown);
        let handle = self
            .handle
            .get_mut()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take();
        if let Some(handle) = handle {
            let _ = handle.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{Body, Command, Part};

    fn falling_body(id: u64, y: f32) -> Body {
        Body {
            id,
            position: [5.0, y, 5.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
            velocity: [0.0; 3],
            parts: vec![Part {
                offset: [0.0; 3],
                size: [1.0; 3],
                density: 2500.0,
                friction: 0.6,
                restitution: 0.0,
                block: "minecraft:oak_planks".into(),
            }],
            wing: None,
        }
    }

    #[test]
    fn catch_up_is_capped_while_active_and_wider_while_idle() {
        let fixed = 1.0 / 120.0;
        // A 1 s stall advances only four fixed steps while active.
        let active = plan_dt(Duration::from_secs(1), fixed, false) as f64;
        assert!((active - 4.0 * fixed).abs() < 1e-6, "active dt={active}");
        // A steady-state loop of one fixed step is passed through unchanged.
        let normal = plan_dt(Duration::from_secs_f64(fixed), fixed, false) as f64;
        assert!((normal - fixed).abs() < 1e-6);
        // A 50 ms loop (slower than four steps) is capped as well.
        let slow = plan_dt(Duration::from_millis(50), fixed, false) as f64;
        assert!((slow - 4.0 * fixed).abs() < 1e-6);
        // Idle may integrate up to the 0.1 s limit of World::exchange.
        let idle = plan_dt(Duration::from_secs(1), fixed, true) as f64;
        assert!((idle - 0.1).abs() < 1e-6, "idle dt={idle}");
    }

    #[test]
    fn worker_steps_on_its_own_and_publishes_snapshots() {
        let worker = PhysicsWorker::spawn(Config::default()).unwrap();
        let reply = worker.submit(Request {
            commands: vec![Command::AddBody {
                body: falling_body(1, 10.0),
            }],
            ..Request::default()
        });
        let applied = reply.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
        assert_eq!(applied.bodies.len(), 1);
        // No caller is driving the world here; the thread advances it by itself.
        thread::sleep(Duration::from_millis(400));
        let snap = worker.latest().expect("a snapshot was published");
        assert!(snap.fixed_steps > 5, "worker did not step: {}", snap.fixed_steps);
        assert!(worker.published_count() > 0);
        let y = snap.bodies[0].position[1];
        assert!(y < 10.0, "body did not fall: y={y}");
    }

    #[test]
    fn rejected_input_is_reported_once_and_the_worker_keeps_running() {
        let worker = PhysicsWorker::spawn(Config::default()).unwrap();
        let rejected = worker.call(
            Request {
                terrain: Some(vec![crate::protocol::TerrainBox {
                    center: [0.0; 3],
                    size: [0.0, 1.0, 1.0],
                }]),
                ..Request::default()
            },
            Duration::from_secs(5),
        );
        assert!(rejected.is_err());
        let first = worker.take_error();
        assert!(first.as_deref().is_some_and(|e| e.starts_with("R:")), "{first:?}");
        assert!(worker.take_error().is_none(), "reported twice");
        // The worker still answers and keeps stepping.
        let ok = worker.call(Request::default(), Duration::from_secs(5));
        assert!(ok.is_ok());
        thread::sleep(Duration::from_millis(100));
        assert!(worker.latest().is_some());
    }

    #[test]
    fn worker_stops_cleanly_when_dropped() {
        let worker = PhysicsWorker::spawn(Config::default()).unwrap();
        let started = Instant::now();
        drop(worker);
        assert!(started.elapsed() < Duration::from_secs(2), "shutdown hung");
    }

    #[test]
    fn invalid_config_is_rejected_before_the_thread_starts() {
        let bad = Config {
            solver_iterations: 0,
            ..Config::default()
        };
        assert!(PhysicsWorker::spawn(bad).is_err());
    }
}
