package dev.foxphys.neoforge;

import dev.foxphys.FoxPhysBlocks;
import dev.foxphys.FoxPhysMod;
import dev.foxphys.net.FoxPhysNetwork;
import dev.foxphys.nativecore.NativeLoader;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NeoForge entry point: registration on the mod bus, game events via FoxPhysMod.init(). */
@Mod(FoxPhysNeoForge.MOD_ID)
public final class FoxPhysNeoForge {
    public static final String MOD_ID = "foxphys";
    private static final Logger LOGGER = LoggerFactory.getLogger("FoxPhys");

    public FoxPhysNeoForge(IEventBus modBus, ModContainer container) {
        try {
            NativeLoader.ensureLoaded();
            LOGGER.info("FoxPhys native core loaded (NeoForge)");
        } catch (RuntimeException e) {
            LOGGER.error("FoxPhys native core failed to load", e);
        }
        FoxPhysBlocks.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, FoxPhysBlocks::addCreativeEntries);
        modBus.addListener(RegisterPayloadHandlersEvent.class, FoxPhysNetwork::register);
        FoxPhysMod.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.foxphys.client.FoxPhysClient.init(modBus);
        }
    }
}
