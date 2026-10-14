package dev.foxphys;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import dev.foxphys.api.event.FoxPhysEvents;
import dev.foxphys.api.event.PhysicsWorldCreatedEvent;
import dev.foxphys.api.event.PhysicsWorldTickEvent;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class LabManager {
    /** Save file format: {"format":1,"origin":[x,y,z],"state":{...Rust SavedState...}}. */
    public static final int SAVE_FORMAT = 1;
    /** Autosave interval in server ticks (5 minutes). */
    public static final int AUTOSAVE_TICKS = 6000;

    private final Map<RegistryKey<World>, Lab> labs = new HashMap<>();
    private final Map<RegistryKey<World>, Integer> sinceAutosave = new HashMap<>();
    private final Set<UUID> activeVisuals = new HashSet<>();
    private final FoxPhysConfig config;
    private final Interactions interactions;
    /** Single writer thread: file I/O never runs on the server tick. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "foxphys-save");
        t.setDaemon(true);
        return t;
    });

    public LabManager(FoxPhysConfig config) {
        this.config = config;
        this.interactions = new Interactions(this);
    }

    public Interactions interactions() {return interactions;}

    /** The physics world of this dimension, or null when no body has existed yet. */
    public Lab labFor(ServerWorld world) {return labs.get(world.getRegistryKey());}

    /** Returns the physics world of this dimension, creating it on first use with the anchor at `anchor`. */
    public Lab ensure(ServerWorld world, BlockPos anchor) {
        Lab lab = labs.get(world.getRegistryKey());
        if (lab != null && !lab.failed()) return lab;
        // A failed world is replaced: its bodies are already lost, its blocks stay where they were recorded.
        if (lab != null) lab.close();
        lab = new Lab(world, anchor, config, this);
        labs.put(world.getRegistryKey(), lab);
        FoxPhysEvents.PHYSICS_WORLD_CREATED.invoker().accept(new PhysicsWorldCreatedEvent(world, lab.origin()));
        return lab;
    }

    public Lab require(ServerWorld world) {
        Lab lab = labs.get(world.getRegistryKey());
        if (lab == null) throw new IllegalStateException("Физики ещё нет: соберите блоки сборщиком или мёд-клеем, либо /foxphys spawn");
        return lab;
    }

    public void tick(ServerWorld world) {
        Lab lab = labs.get(world.getRegistryKey());
        if (lab == null) return;
        lab.setWind(Wind.at(world));
        lab.tick();
        FoxPhysEvents.PHYSICS_WORLD_TICK.invoker().accept(new PhysicsWorldTickEvent(world));
        int count = sinceAutosave.merge(world.getRegistryKey(), 1, Integer::sum);
        if (count >= AUTOSAVE_TICKS && !lab.failed()) {
            sinceAutosave.put(world.getRegistryKey(), 0);
            try {
                save(world, false); // export here, write on the writer thread
            } catch (RuntimeException e) {
                FoxPhysMod.LOGGER.warn("FoxPhys autosave skipped: {}", e.getMessage());
            }
        }
    }

    /**
     * Writes the save right after a change to assemblies, so a crash cannot lose the blocks
     * that were moved into bodies. A failed write is logged; the operation itself already succeeded.
     */
    public void persistNow(ServerWorld world) {
        try {
            save(world, false);
        } catch (RuntimeException e) {
            FoxPhysMod.LOGGER.error("FoxPhys: immediate save failed, blocks may be lost on crash: {}", e.getMessage());
        }
    }

    public void clear(ServerWorld world) {
        // The save file is kept on purpose: /foxphys restore brings the stand back.
        sinceAutosave.remove(world.getRegistryKey());
        Lab lab = labs.remove(world.getRegistryKey());
        if (lab != null) lab.close();
        interactions.releaseAll();
    }

    /** Saves the stand of this dimension. Export runs here (server thread); the write is async unless {@code sync}. */
    public String save(ServerWorld world, boolean sync) {
        Lab lab = require(world);
        JsonObject state = lab.exportState();
        JsonObject root = new JsonObject();
        root.addProperty("format", SAVE_FORMAT);
        root.add("origin", Protocol.indices(lab.origin().getX(), lab.origin().getY(), lab.origin().getZ()));
        root.add("state", state);
        root.add("assemblies", lab.assembliesJson());
        String text = root.toString();
        Path file = saveFile(world);
        if (sync) {
            write(file, text);
        } else {
            writer.submit(() -> write(file, text));
        }
        return file.getFileName().toString();
    }

    /** Loads the saved state of this dimension into the existing stand. */
    public String restore(ServerWorld world) {
        Lab lab = require(world);
        Path file = saveFile(world);
        if (!Files.exists(file)) throw new IllegalStateException("Сохранения нет: " + file.getFileName());
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Не удалось прочитать сохранение: " + e.getMessage());
        }
        if (root.get("format") == null || root.get("format").getAsInt() != SAVE_FORMAT) {
            throw new IllegalStateException("Неизвестный формат сохранения");
        }
        // Body positions and assembly cells are relative to the stand origin. Restoring into a stand
        // with another origin would shift every body, so refuse before anything changes in the world.
        JsonArray o = root.getAsJsonArray("origin");
        if (o == null || o.size() != 3) throw new IllegalStateException("В сохранении нет origin");
        BlockPos saved = new BlockPos(o.get(0).getAsInt(), o.get(1).getAsInt(), o.get(2).getAsInt());
        if (!saved.equals(lab.origin())) {
            throw new IllegalStateException("Сохранение сделано для стенда в " + saved.getX() + " " + saved.getY() + " " + saved.getZ()
                    + ", текущий стенд в " + lab.origin().getX() + " " + lab.origin().getY() + " " + lab.origin().getZ()
                    + ". Встаньте в точку сохранения (execute positioned X Y Z run foxphys spawn ...) и повторите restore");
        }
        String message = lab.importState(root.getAsJsonObject("state"));
        lab.loadAssemblies(root.getAsJsonArray("assemblies"));
        return message;
    }

    private Path saveFile(ServerWorld world) {
        String name = world.getRegistryKey().getValue().toString().replace(':', '_').replace('/', '_');
        return world.getServer().getSavePath(WorldSavePath.ROOT).resolve("foxphys").resolve(name + ".json");
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            FoxPhysMod.LOGGER.error("FoxPhys save failed for {}", file, e);
            throw new IllegalStateException("Сохранение не записано: " + e.getMessage());
        }
    }

    /** Server stop: export every stand synchronously, then stop the writer and release native worlds. */
    public void shutdown() {
        Set<Lab> saved = new HashSet<>();
        for (Lab lab : labs.values()) {
            if (lab.failed()) continue;
            try {
                save(lab.world(), true);
                saved.add(lab);
            } catch (RuntimeException e) {
                FoxPhysMod.LOGGER.warn("FoxPhys save on shutdown failed: {}", e.getMessage());
            }
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) FoxPhysMod.LOGGER.warn("FoxPhys save writer still busy");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Saved stands keep their assembled blocks inside the saved bodies; unsaved ones give the blocks back.
        for (Lab lab : labs.values()) lab.close(!saved.contains(lab));
        labs.clear(); sinceAutosave.clear(); activeVisuals.clear();
        interactions.releaseAll();
    }

    public void clearAll() {labs.values().forEach(Lab::close);labs.clear();sinceAutosave.clear();activeVisuals.clear();interactions.releaseAll();}
    public void track(UUID id) {activeVisuals.add(id);}
    public void untrack(UUID id) {activeVisuals.remove(id);}
    public boolean owns(UUID id) {return activeVisuals.contains(id);}
}
