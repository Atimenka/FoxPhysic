#!/bin/sh
# Compiles and runs AttachSmoke (soft-body attachments, wind, block table) against the NeoForge build output.
# Needs JDK 21 and the Gradle cache of the NeoForge build (GRADLE_USER_HOME, default ~/.gradle).
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GRADLE_HOME_DIR=${GRADLE_USER_HOME:-$HOME/.gradle}
JAVA_BIN=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/bin
WORK=$(mktemp -d)
NATIVE_PATH=${FOXPHYS_NATIVE_PATH:-$ROOT/neoforge/prebuilt/natives/linux-x86_64/libfoxphys_core.so}
[ -f "$NATIVE_PATH" ] || { echo "Linux native library not found: $NATIVE_PATH"; exit 1; }
CP=$ROOT/neoforge/build/classes/java/main:$ROOT/common/build/classes/java/main:$ROOT/neoforge/src/main/resources:$ROOT/common/src/main/resources
for j in $(find "$GRADLE_HOME_DIR/caches" -name '*.jar' | grep -v 'installer\|fatjar\|mergetool\|binarypatcher\|jst-cli\|AutoRenaming\|instrumented\|cli-utils\|sources\|javadoc\|userdev\|moddev' ); do CP=$CP:$j; done
CP=$CP:$(find "$GRADLE_HOME_DIR/caches" -name 'binaryWithNeoForge*_output.jar' -o -name 'minecraft_1.21.1_server.jar' | paste -sd: -)
"$JAVA_BIN/javac" -proc:none -cp "$CP" -d "$WORK" "$ROOT/validation/attach/AttachSmoke.java"
exec "$JAVA_BIN/java" -Xmx512m -Dfoxphys.nativePath="$NATIVE_PATH" -cp "$WORK:$CP" dev.foxphys.AttachSmoke
