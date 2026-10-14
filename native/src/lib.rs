//! JVM boundary. Opaque monotonically allocated handles, never Java-owned raw
//! pointers. JNI exceptions report validation errors; panics cannot unwind
//! across the ABI. The registry mutex serializes access to each native world.
pub mod assembler;
pub mod engine;
pub mod fluid_world;
pub mod forces;
pub mod mechanics;
pub mod protocol;
pub mod soft;
pub mod soft_world;
pub mod sph;
pub mod water;
pub mod worker;

use engine::World;
use jni::{
    objects::{JClass, JString},
    sys::{jboolean, jint, jlong, jstring},
    JNIEnv,
};
use protocol::{Config, Request, ABI_VERSION};
use std::{
    collections::BTreeMap,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{Arc, Mutex, OnceLock},
    time::Duration,
};
use worker::PhysicsWorker;

/// Physics workers (one thread each). Only the map lookup holds the lock; calls run without it.
struct WorkerRegistry {
    next: i64,
    workers: BTreeMap<i64, Arc<PhysicsWorker>>,
}
static WORKERS: OnceLock<Mutex<WorkerRegistry>> = OnceLock::new();
const MAX_WORKERS: usize = 8;
const CALL_TIMEOUT: Duration = Duration::from_millis(2000);

fn workers() -> &'static Mutex<WorkerRegistry> {
    WORKERS.get_or_init(|| {
        Mutex::new(WorkerRegistry {
            next: 1,
            workers: BTreeMap::new(),
        })
    })
}
fn find_worker(handle: jlong) -> Result<Arc<PhysicsWorker>, String> {
    let r = workers()
        .lock()
        .map_err(|_| "worker registry poisoned".to_string())?;
    r.workers
        .get(&handle)
        .cloned()
        .ok_or_else(|| "invalid/closed physics worker handle".to_string())
}

struct Registry {
    next: i64,
    worlds: BTreeMap<i64, World>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| {
        Mutex::new(Registry {
            next: 1,
            worlds: BTreeMap::new(),
        })
    })
}
fn guarded<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    catch_unwind(AssertUnwindSafe(f))
        .map_err(|_| "Rust panic trapped at JNI boundary; restart the server".to_string())?
}
fn read_string(env: &mut JNIEnv<'_>, input: &JString<'_>) -> Result<String, String> {
    let s: String = env
        .get_string(input)
        .map_err(|e| format!("JNI string: {e}"))?
        .into();
    if s.len() > 8 * 1024 * 1024 {
        return Err("JNI request exceeds 8 MiB".into());
    }
    Ok(s)
}
fn fail(env: &mut JNIEnv<'_>, error: String) {
    let _ = env.throw_new("java/lang/IllegalStateException", error);
}

#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_abiVersion(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    ABI_VERSION
}

#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_create(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
) -> jlong {
    let result = guarded(|| {
        let text = read_string(&mut env, &config)?;
        let cfg: Config = serde_json::from_str(&text).map_err(|e| format!("config JSON: {e}"))?;
        let world = World::new(cfg)?;
        let mut r = registry()
            .lock()
            .map_err(|_| "native registry poisoned".to_string())?;
        if r.worlds.len() >= 8 {
            return Err("native world limit: 8".into());
        }
        let h = r.next;
        r.next = r.next.checked_add(1).ok_or("native handle exhausted")?;
        r.worlds.insert(h, world);
        Ok(h)
    });
    match result {
        Ok(h) => h,
        Err(e) => {
            fail(&mut env, e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_exchange(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    input: JString<'_>,
) -> jstring {
    let result = guarded(|| {
        let text = read_string(&mut env, &input)?;
        let request: Request =
            serde_json::from_str(&text).map_err(|e| format!("request JSON: {e}"))?;
        let mut r = registry()
            .lock()
            .map_err(|_| "native registry poisoned".to_string())?;
        let world = r
            .worlds
            .get_mut(&handle)
            .ok_or("invalid/closed native world handle")?;
        let snapshot = world.exchange(request)?;
        let output = serde_json::to_string(&snapshot).map_err(|e| format!("snapshot JSON: {e}"))?;
        env.new_string(output)
            .map(|s| s.into_raw())
            .map_err(|e| format!("JNI output: {e}"))
    });
    match result {
        Ok(s) => s,
        Err(e) => {
            fail(&mut env, e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_destroy(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    let result = guarded(|| {
        let mut r = registry()
            .lock()
            .map_err(|_| "native registry poisoned".to_string())?;
        if r.worlds.remove(&handle).is_none() {
            return Err("invalid/already closed native world handle".into());
        }
        Ok(())
    });
    if let Err(e) = result {
        fail(&mut env, e);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_workerCreate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
) -> jlong {
    let result = guarded(|| {
        let text = read_string(&mut env, &config)?;
        let cfg: Config = serde_json::from_str(&text).map_err(|e| format!("config JSON: {e}"))?;
        let worker = PhysicsWorker::spawn(cfg)?;
        let mut r = workers()
            .lock()
            .map_err(|_| "worker registry poisoned".to_string())?;
        if r.workers.len() >= MAX_WORKERS {
            return Err(format!("physics worker limit: {MAX_WORKERS}"));
        }
        let h = r.next;
        r.next = r.next.checked_add(1).ok_or("worker handle exhausted")?;
        r.workers.insert(h, Arc::new(worker));
        Ok(h)
    });
    match result {
        Ok(h) => h,
        Err(e) => {
            fail(&mut env, e);
            0
        }
    }
}

/// Fire-and-forget input: applied by the worker between steps, without advancing time.
#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_workerPush(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    input: JString<'_>,
) {
    let result = guarded(|| {
        let text = read_string(&mut env, &input)?;
        let request: Request =
            serde_json::from_str(&text).map_err(|e| format!("request JSON: {e}"))?;
        let worker = find_worker(handle)?;
        // Rejections are reported later through workerTakeError.
        let _ = worker.submit(request);
        Ok(())
    });
    if let Err(e) = result {
        fail(&mut env, e);
    }
}

/// Synchronous input (commands, export, import). Waits at most two seconds.
#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_workerCall(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    input: JString<'_>,
) -> jstring {
    let result = guarded(|| {
        let text = read_string(&mut env, &input)?;
        let request: Request =
            serde_json::from_str(&text).map_err(|e| format!("request JSON: {e}"))?;
        let worker = find_worker(handle)?;
        let snapshot = worker.call(request, CALL_TIMEOUT)?;
        let output = serde_json::to_string(&snapshot).map_err(|e| format!("snapshot JSON: {e}"))?;
        env.new_string(output)
            .map(|s| s.into_raw())
            .map_err(|e| format!("JNI output: {e}"))
    });
    match result {
        Ok(s) => s,
        Err(e) => {
            fail(&mut env, e);
            std::ptr::null_mut()
        }
    }
}

/// Latest published snapshot as JSON, or null before the first step.
#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_workerLatest(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let result = guarded(|| {
        let worker = find_worker(handle)?;
        let Some(snapshot) = worker.latest() else {
            return Ok(std::ptr::null_mut());
        };
        let output = serde_json::to_string(&*snapshot).map_err(|e| format!("snapshot JSON: {e}"))?;
        env.new_string(output)
            .map(|s| s.into_raw())
            .map_err(|e| format!("JNI output: {e}"))
    });
    match result {
        Ok(s) => s,
        Err(e) => {
            fail(&mut env, e);
            std::ptr::null_mut()
        }
    }
}

/// "" when nothing is pending, otherwise "F:<fatal>" or "R:<rejected input>".
#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_workerTakeError(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let result = guarded(|| {
        let worker = find_worker(handle)?;
        let text = worker.take_error().unwrap_or_default();
        env.new_string(text)
            .map(|s| s.into_raw())
            .map_err(|e| format!("JNI output: {e}"))
    });
    match result {
        Ok(s) => s,
        Err(e) => {
            fail(&mut env, e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_workerSetIdle(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    idle: jboolean,
) {
    let result = guarded(|| {
        find_worker(handle)?.set_idle(idle != 0);
        Ok(())
    });
    if let Err(e) = result {
        fail(&mut env, e);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_workerDestroy(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    let result = guarded(|| {
        let removed = {
            let mut r = workers()
                .lock()
                .map_err(|_| "worker registry poisoned".to_string())?;
            r.workers.remove(&handle)
        };
        // Dropping the last Arc stops the thread and joins it, outside the registry lock.
        removed
            .map(drop)
            .ok_or_else(|| "invalid/already closed physics worker handle".to_string())
    });
    if let Err(e) = result {
        fail(&mut env, e);
    }
}

/// Soft bodies (XPBD). One process-wide registry; requests and responses are JSON (see soft_world.rs).
static SOFT: OnceLock<Mutex<soft_world::SoftWorld>> = OnceLock::new();

#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_softExchange<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let result = guarded(|| {
        let text = read_string(&mut env, &request)?;
        let cell = SOFT.get_or_init(|| Mutex::new(soft_world::SoftWorld::new()));
        let mut world = cell.lock().map_err(|_| "soft registry poisoned".to_string())?;
        world.exchange(&text)
    });
    match result {
        Ok(text) => match env.new_string(text) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                fail(&mut env, format!("JNI string: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            fail(&mut env, e);
            std::ptr::null_mut()
        }
    }
}

/// SPH water pools. One process-wide registry; requests and responses are JSON (see fluid_world.rs).
static FLUID: OnceLock<Mutex<fluid_world::FluidWorld>> = OnceLock::new();

#[no_mangle]
pub extern "system" fn Java_dev_foxphys_nativecore_NativeBridge_fluidExchange<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let result = guarded(|| {
        let text = read_string(&mut env, &request)?;
        let cell = FLUID.get_or_init(|| Mutex::new(fluid_world::FluidWorld::new()));
        let mut world = cell.lock().map_err(|_| "fluid registry poisoned".to_string())?;
        world.exchange(&text)
    });
    match result {
        Ok(text) => match env.new_string(text) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                fail(&mut env, format!("JNI string: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            fail(&mut env, e);
            std::ptr::null_mut()
        }
    }
}
