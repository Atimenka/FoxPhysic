#!/usr/bin/env bash
# Optional Linux -> Windows GNU packaging. Native Windows builds use MSVC
# automatically through gradlew.bat build and do not need this script.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v x86_64-w64-mingw32-gcc >/dev/null || { echo 'Install mingw-w64 cross compiler first'; exit 1; }
cd "$ROOT/native"
rustup target add x86_64-pc-windows-gnu
cargo test --locked
cargo build --locked --release --target x86_64-pc-windows-gnu
cd "$ROOT"
mkdir -p build/generated/natives/natives/windows-x86_64
cp native/target/x86_64-pc-windows-gnu/release/foxphys_core.dll build/generated/natives/natives/windows-x86_64/
./gradlew build -PskipNative --no-daemon
# skipNative skips compilation/copying, NOT embedding of generated resources.
echo "Cross-built Windows mod: $(ls "$ROOT"/build/libs/foxphys-*.jar | grep -v -- -sources | grep -v -- -dev | head -n1)"
