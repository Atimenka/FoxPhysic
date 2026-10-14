package dev.foxphys.api.event;

import net.neoforged.bus.api.Event;

/**
 * Base of every FoxPhys event. Events are posted on the server thread to
 * {@code NeoForge.EVENT_BUS}. Listeners must not change the physics world from inside
 * an event; they may read state, or cancel an event that allows it.
 */
public abstract class FoxPhysEvent extends Event {
}
