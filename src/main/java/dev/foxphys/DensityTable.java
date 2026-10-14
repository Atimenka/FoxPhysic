package dev.foxphys;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Material densities from {@code assets/foxphys/density-table.json} (source: docs/density-table-alpha.6.json).
 * Keys are block paths ("oak_planks"); values are kg/m^3. A missing or broken file is logged once and
 * leaves the table empty, so the name heuristics in {@link Lab} keep working.
 */
public final class DensityTable {
    static final String RESOURCE = "/assets/foxphys/density-table.json";
    private static final Map<String, Double> VALUES = load();
    /** Densities registered through the public API. Checked before the table. */
    private static final Map<String, Double> OVERRIDES = new ConcurrentHashMap<>();

    private DensityTable() {}

    /**
     * Density of a key in kg/m^3, or null when neither a registered override nor the table has it.
     * A key is a block path ("oak_planks") or a full id ("mymod:rock"); overrides win over the table.
     */
    public static Double get(String key) {
        Double overridden = OVERRIDES.get(key);
        return overridden != null ? overridden : VALUES.get(key);
    }

    /** The density registered through the API for a key (exact key only), or null. */
    public static Double overridden(String key) {
        return OVERRIDES.get(key);
    }

    /** Removes an API-registered density. Returns false when the key was not registered through the API. */
    public static boolean unregister(String key) {
        return OVERRIDES.remove(key) != null;
    }

    /** Copy of the densities registered through the API. */
    public static Map<String, Double> registered() {
        return Map.copyOf(OVERRIDES);
    }

    /**
     * Registers or replaces a density (kg/m^3) for a key: a block path or a full id. Bodies assembled
     * earlier keep their mass; the value applies to assemblies made after this call.
     */
    public static void register(String key, double kgPerM3) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Density key is empty");
        if (!Double.isFinite(kgPerM3) || kgPerM3 <= 0 || kgPerM3 > 100_000) {
            throw new IllegalArgumentException("Density must be in (0, 100000] kg/m^3: " + kgPerM3);
        }
        OVERRIDES.put(key, kgPerM3);
    }

    /** Number of materials loaded from the table (overrides not included). */
    public static int size() {
        return VALUES.size();
    }

    /** Number of densities registered through the API. */
    public static int overrideCount() {
        return OVERRIDES.size();
    }

    private static Map<String, Double> load() {
        Map<String, Double> table = new HashMap<>();
        try (InputStream in = DensityTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                FoxPhysMod.LOGGER.warn("FoxPhys: {} is missing; density falls back to block-name heuristics", RESOURCE);
                return table;
            }
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                // Metadata keys start with "_"; the rest are material objects with a "density" number.
                if (e.getKey().startsWith("_") || !e.getValue().isJsonObject()) continue;
                JsonObject material = e.getValue().getAsJsonObject();
                if (!material.has("density") || !material.get("density").isJsonPrimitive()) continue;
                double density = material.get("density").getAsDouble();
                if (!Double.isFinite(density) || density <= 0) {
                    FoxPhysMod.LOGGER.warn("FoxPhys: density table entry '{}' is not positive; skipped", e.getKey());
                    continue;
                }
                table.put(e.getKey(), density);
            }
        } catch (IOException | RuntimeException e) {
            FoxPhysMod.LOGGER.warn("FoxPhys: density table is unreadable; using block-name heuristics", e);
            table.clear();
        }
        FoxPhysMod.LOGGER.info("FoxPhys: density table loaded, {} materials", table.size());
        return table;
    }
}
