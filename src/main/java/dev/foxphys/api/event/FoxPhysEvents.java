package dev.foxphys.api.event;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Event registry of FoxPhys on Fabric. Register a listener with {@code EVENT.register(e -> ...)}.
 * Listeners run in registration order on the server thread. Example:
 * {@code FoxPhysEvents.ASSEMBLE_REQUESTED.register(e -> e.setCanceled(true));}
 */
public final class FoxPhysEvents {
    private FoxPhysEvents() {}

    /** Before blocks become a body. Cancellable. */
    public static final Event<Consumer<AssembleRequestedEvent>> ASSEMBLE_REQUESTED = create();
    public static final Event<Consumer<BodyAssembledEvent>> BODY_ASSEMBLED = create();
    public static final Event<Consumer<BodyDisassembledEvent>> BODY_DISASSEMBLED = create();
    public static final Event<Consumer<BodySpawnedEvent>> BODY_SPAWNED = create();
    public static final Event<Consumer<PhysicsWorldCreatedEvent>> PHYSICS_WORLD_CREATED = create();
    public static final Event<Consumer<PhysicsWorldTickEvent>> PHYSICS_WORLD_TICK = create();
    public static final Event<Consumer<SoftBodySpawnedEvent>> SOFT_BODY_SPAWNED = create();
    public static final Event<Consumer<SoftBodyRemovedEvent>> SOFT_BODY_REMOVED = create();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Event<Consumer<T>> create() {
        return (Event) EventFactory.createArrayBacked(Consumer.class, listeners -> payload -> {
            for (Consumer listener : listeners) listener.accept(payload);
        });
    }
}
