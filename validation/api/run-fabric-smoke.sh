#!/bin/sh
# Compiles and runs ApiSmokeFabric against the Fabric build output (run after `gradlew compileJava -PskipNative`).
# Needs JDK 21, the Loom cache (minecraft-merged with Yarn names, fabric-api-base) and the Gradle cache of the build.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GRADLE_HOME_DIR=${GRADLE_USER_HOME:-$HOME/.gradle}
LOOM_CACHE=${LOOM_CACHE:-$ROOT/.gradle/loom-cache}
JAVA_BIN=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/bin
WORK=$(mktemp -d)
MERGED=$(find "$LOOM_CACHE" "$GRADLE_HOME_DIR/caches/fabric-loom" -name 'minecraft-merged-*yarn*.jar' -o -name 'minecraft-merged-1.21.1-net.fabricmc.yarn*.jar' | head -1)
[ -n "$MERGED" ] || { echo "minecraft-merged (Yarn) jar not found"; exit 1; }
API_BASE=$(find "$LOOM_CACHE" "$GRADLE_HOME_DIR/caches" -name 'fabric-api-base-*.jar' | grep -v sources | head -1)
CP=$ROOT/build/classes/java/main:$ROOT/src/main/resources:$MERGED:$API_BASE
for j in $(find "$GRADLE_HOME_DIR/caches/modules-2" -name '*.jar' | grep 'gson\|fastutil\|brigadier\|joml\|guava\|log4j\|slf4j\|datafixer\|commons-\|netty\|authlib\|httpclient\|httpcore\|jna\|oshi\|lwjgl\|jtracy\|blocklist\|text2speech\|icu4j\|fabric-api-base\|fabric-loader' | grep -v sources ); do CP=$CP:$j; done
"$JAVA_BIN/javac" -proc:none -cp "$CP" -d "$WORK" "$ROOT/validation/api/ApiSmokeFabric.java"
exec "$JAVA_BIN/java" -Xmx512m -Dfoxphys.nativePath="$ROOT/neoforge/prebuilt/natives/linux-x86_64/libfoxphys_core.so" -cp "$WORK:$CP" ApiSmokeFabric
