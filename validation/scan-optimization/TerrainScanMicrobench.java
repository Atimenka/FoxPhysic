import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Isolated allocation/throughput check for the current terrain-scan bookkeeping.
 * This is deliberately not presented as an end-to-end Minecraft tick benchmark.
 */
public final class TerrainScanMicrobench {
    private static final int UNIQUE_CELLS = 60_000;
    private static final int INPUT_CELLS = 90_000; // 30,000 are repeats from overlapping regions
    private static final int UNIQUE_BOXES = 256;
    private static final int INPUT_BOXES = UNIQUE_BOXES * 2;
    private static final int WARMUP = 20;
    private static final int MEASURE = 60;
    private static final long[] CELL_KEYS = makeCellKeys();
    private static final Geometry[] GEOMETRY = makeGeometry();
    private static final LongOpenHashSet REUSED_PRIMITIVE_SET = new LongOpenHashSet();
    private static volatile long sink;

    private record Geometry(double cx, double cy, double cz, double sx, double sy, double sz) {}

    private static long packKey(int x, int y, int z) {
        return ((long) (x + 4096) & 0x1FFFL) << 26
                | ((long) (y + 4096) & 0x1FFFL) << 13
                | ((long) (z + 4096) & 0x1FFFL);
    }

    private static long[] makeCellKeys() {
        long[] keys = new long[INPUT_CELLS];
        for (int i = 0; i < UNIQUE_CELLS; i++) {
            int x = i % 400;
            int z = (i / 400) % 50;
            int y = i / 20_000;
            keys[i] = packKey(x, y, z);
        }
        System.arraycopy(keys, 0, keys, UNIQUE_CELLS, INPUT_CELLS - UNIQUE_CELLS);
        return keys;
    }

    private static Geometry[] makeGeometry() {
        Geometry[] boxes = new Geometry[INPUT_BOXES];
        for (int i = 0; i < UNIQUE_BOXES; i++) {
            boxes[i] = new Geometry(i * 0.25, (i % 11) * 0.5, (i % 17) * 0.125,
                    1.0, 0.5 + (i % 3) * 0.25, 1.0);
            boxes[i + UNIQUE_BOXES] = boxes[i];
        }
        return boxes;
    }

    private static long oldBoxedScan() {
        Set<Long> seen = new HashSet<>();
        for (long key : CELL_KEYS) seen.add(key);
        return seen.size();
    }

    private static long newPrimitiveScan() {
        REUSED_PRIMITIVE_SET.clear();
        for (long key : CELL_KEYS) REUSED_PRIMITIVE_SET.add(key);
        return REUSED_PRIMITIVE_SET.size();
    }

    private static JsonArray vector(double x, double y, double z) {
        JsonArray values = new JsonArray();
        values.add(x);
        values.add(y);
        values.add(z);
        return values;
    }

    private static long oldJsonTerrainKeys() {
        Map<String, JsonObject> terrain = new LinkedHashMap<>();
        for (Geometry box : GEOMETRY) {
            JsonObject json = new JsonObject();
            json.add("center", vector(box.cx(), box.cy(), box.cz()));
            json.add("size", vector(box.sx(), box.sy(), box.sz()));
            terrain.putIfAbsent(json.toString(), json);
        }
        return terrain.size();
    }

    private static long newTypedTerrainKeys() {
        Set<Geometry> terrain = new LinkedHashSet<>();
        for (Geometry box : GEOMETRY) {
            terrain.add(new Geometry(box.cx(), box.cy(), box.cz(), box.sx(), box.sy(), box.sz()));
        }
        return terrain.size();
    }

    private static void run(String name, java.util.function.LongSupplier operation) {
        for (int i = 0; i < WARMUP; i++) sink += operation.getAsLong();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (bean.isThreadAllocatedMemorySupported() && !bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        long tid = Thread.currentThread().threadId();
        long allocatedBefore = bean.getThreadAllocatedBytes(tid);
        long started = System.nanoTime();
        long total = 0;
        for (int i = 0; i < MEASURE; i++) total += operation.getAsLong();
        long elapsed = System.nanoTime() - started;
        long allocated = bean.getThreadAllocatedBytes(tid) - allocatedBefore;
        sink += total;
        System.out.printf("%s: %.3f ms/scan, %.1f KiB allocated/scan, result=%d%n",
                name, elapsed / 1_000_000.0 / MEASURE, allocated / 1024.0 / MEASURE, total / MEASURE);
    }

    public static void main(String[] args) {
        System.out.printf("fixture: %d cell inputs (%d unique), %d geometry inputs (%d unique), %d measured scans%n",
                INPUT_CELLS, UNIQUE_CELLS, INPUT_BOXES, UNIQUE_BOXES, MEASURE);
        run("boxed HashSet<Long>", TerrainScanMicrobench::oldBoxedScan);
        run("reused LongOpenHashSet", TerrainScanMicrobench::newPrimitiveScan);
        run("JSON-string geometry keys", TerrainScanMicrobench::oldJsonTerrainKeys);
        run("typed geometry keys", TerrainScanMicrobench::newTypedTerrainKeys);
    }
}
