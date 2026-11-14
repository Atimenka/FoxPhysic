# Terrain-scan bookkeeping microbenchmark

`TerrainScanMicrobench.java` isolates two allocation-heavy parts of the Minecraft terrain scanner. It compares the prior boxed `HashSet<Long>` with the reused `LongOpenHashSet`, and JSON-string geometry keys with numeric record keys. The fixture has 90,000 visited-cell inputs (60,000 unique, with 30,000 duplicates from overlapping regions) and 512 shape inputs (256 unique).

This is a **synthetic bookkeeping microbenchmark**, not a Minecraft world scan or end-to-end tick profile. Block/chunk lookups, collision-shape work, JNI, and rendering are not represented; do not interpret these figures as an overall server-TPS multiplier.

## Run

After resolving the project Gradle dependencies, use JDK 21:

```sh
FASTUTIL=$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1/it.unimi.dsi/fastutil/8.5.12" -name 'fastutil-8.5.12.jar' -print -quit)
GSON=$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1" -name 'gson-2.10.1.jar' -print -quit)
mkdir -p /tmp/foxphys-scan-bench
javac -cp "$FASTUTIL:$GSON" -d /tmp/foxphys-scan-bench validation/scan-optimization/TerrainScanMicrobench.java
java -Xms256m -Xmx1g -cp "/tmp/foxphys-scan-bench:$FASTUTIL:$GSON" TerrainScanMicrobench
```

## 2026-10-14 result

On the Arena Linux sandbox, OpenJDK 21.0.12, after 20 warm-up scans and over 60 measured scans:

| Bookkeeping | ms/scan | KiB allocated/scan | Result |
|---|---:|---:|---:|
| boxed `HashSet<Long>` | 23.633 | 13,649.6 | 60,000 unique cells |
| reused `LongOpenHashSet` | 0.687 | 0.0 | 60,000 unique cells |
| JSON-string geometry keys | 2.422 | 979.5 | 256 unique boxes |
| typed geometry keys | 0.475 | 46.1 | 256 unique boxes |

Numbers are a single local run and are JVM/hardware dependent. The benchmark confirms that the isolated data-structure paths preserve unique counts and substantially reduce their own allocations; it does not prove a particular in-game tick improvement.
