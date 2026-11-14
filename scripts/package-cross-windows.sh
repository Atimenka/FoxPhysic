#!/usr/bin/env bash
# Optional Linux -> Windows GNU cross-build. No Visual Studio is needed on the build host.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v x86_64-w64-mingw32-gcc >/dev/null || { echo 'Install the x86_64 MinGW-w64 GCC toolchain first'; exit 1; }
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
export PATH="${CARGO_HOME:-$HOME/.cargo}/bin:$PATH"
cd "$ROOT/native"
rustup target add x86_64-pc-windows-gnu
cargo test --locked
cargo build --locked --release --target x86_64-pc-windows-gnu
cd "$ROOT"
DLL="$ROOT/native/target/x86_64-pc-windows-gnu/release/foxphys_core.dll"
test -s "$DLL"
mkdir -p "$ROOT/neoforge/prebuilt/natives/windows-x86_64"
cp "$DLL" "$ROOT/neoforge/prebuilt/natives/windows-x86_64/foxphys_core.dll"
./gradlew build --no-daemon --max-workers=1
printf 'Cross-built Windows libraries are packaged in:\n'
find "$ROOT/fabric/build/libs" "$ROOT/neoforge/build/libs" -maxdepth 1 -type f -name '*.jar' -printf '%p\n' | sort
