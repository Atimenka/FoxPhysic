package dev.foxphys;

import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.GsonBuilder;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import net.neoforged.fml.loading.FMLPaths;

public final class FoxPhysConfig {
    public float gravity = 9.81f;
    public float airDensity = 1.225f;
    public float waterDensity = 998.2f;
    public int stepHz = 120;
    /** Most bodies at once. 0 = no limit (the per-world cell budget in Assembler still applies). */
    public int maxBodies = 64;
    public int environmentScanInterval = 1;
    public int visualUpdateInterval = 1;
    public int waterDisplayStride = 2;

    public String nativeJson() {
        var c = new com.google.gson.JsonObject();
        c.addProperty("gravity", gravity); c.addProperty("air_density", airDensity);
        c.addProperty("water_density", waterDensity); c.addProperty("step_hz", stepHz);
        c.addProperty("max_bodies", maxBodies);
        return c.toString();
    }
    private void validate() {
        if (!Float.isFinite(gravity) || gravity < 0.1f || gravity > 30f ||
            !Float.isFinite(airDensity) || airDensity < 0 || airDensity > 20 ||
            !Float.isFinite(waterDensity) || waterDensity < 100 || waterDensity > 2000 ||
            stepHz < 30 || stepHz > 240 || maxBodies < 0 || maxBodies > 10000 ||
            environmentScanInterval < 1 || environmentScanInterval > 200 ||
            visualUpdateInterval < 1 || visualUpdateInterval > 10 ||
            waterDisplayStride < 1 || waterDisplayStride > 4) {
            throw new IllegalArgumentException("FoxPhys config values out of range");
        }
    }
    public static FoxPhysConfig load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("foxphys.json");
        Gson gson = new GsonBuilder().setPrettyPrinting()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
        try {
            if (Files.exists(path)) {
                FoxPhysConfig cfg = gson.fromJson(Files.readString(path), FoxPhysConfig.class);
                if (cfg == null) throw new IllegalArgumentException("Empty config");
                cfg.validate(); return cfg;
            }
            var cfg = new FoxPhysConfig();
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(cfg)); return cfg;
        } catch (Exception e) {
            FoxPhysMod.LOGGER.error("Invalid/unreadable foxphys.json; using safe defaults", e);
            return new FoxPhysConfig();
        }
    }
}
