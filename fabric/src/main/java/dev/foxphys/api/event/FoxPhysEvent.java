package dev.foxphys.api.event;

/**
 * Base of every FoxPhys event payload. Listeners are registered on the static {@link FoxPhysEvents}
 * fields and are called on the server thread. Listeners must not change the physics world from inside
 * an event; they may read state, or cancel an event that allows it.
 */
public abstract class FoxPhysEvent {
}
