# FoxPhys 0.1.1-beta-release — source candidate

## Status

This bundle contains the sources with the requested version string `0.1.1-beta-release`. It is **not a compiled or live-validated beta release**. The existing alpha.7 implementation and packaging fix have headless/build evidence, but this version string has not yet been built from this source bundle. The Windows DLL has not been run on Windows, and NeoForge has not been launched in Minecraft. Do not label gameplay acceptance as passed until the live checklist is completed.

Water and soft bodies remain **extremely unstable**. Use a backed-up disposable world. Explosions can discard physical bodies; the solver limit is 512 parts/colliders. See `docs/KNOWN_LIMITATIONS.md` and `docs/IN_GAME_TEST_CHECKLIST.md` before publishing binaries.

## Build the two mod jars (Windows PowerShell, JDK 21)

Run in the repository root:

```powershell
java -version
$env:JAVA_TOOL_OPTIONS = "-Xmx1024m -XX:+UseSerialGC -XX:ActiveProcessorCount=1"
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx512m -XX:+UseSerialGC -XX:ActiveProcessorCount=1" :fabric:build :neoforge:build
```

The checked-in prebuilt native fallbacks are packaged by default; this does not require Visual Studio. Outputs:

- `fabric/build/libs/foxphys-fabric-0.1.1-beta-release.jar`
- `fabric/build/libs/foxphys-fabric-0.1.1-beta-release-sources.jar`
- `neoforge/build/libs/foxphys-neoforge-0.1.1-beta-release.jar`
- `neoforge/build/libs/foxphys-neoforge-0.1.1-beta-release-sources.jar`

On Linux, use `bash ./gradlew --no-daemon --max-workers=1 :fabric:build :neoforge:build` with JDK 21. If the wrapper/dependencies are not cached, Gradle downloads them on first build.

The build must finish `BUILD SUCCESSFUL`; Fabric should report `JNI_SMOKE_OK` and `PROTOCOL_SMOKE_OK`. Both loader checks verify `NativeLoader` and `NativeBridge` are present and loadable from the packaged mod archive. Also run the standalone API, fluid and attachment scripts from `validation/README.md`. Record SHA-256 for the four output jars.

## GitHub: apply this source bundle to the existing repository

This ZIP is not a `.git` repository and contains no authentication data. Commands below assume a normal clone and an authenticated Git installation on your machine. Extract the ZIP's root contents into the clone; keep your local credentials private.

Direct push to the existing lowercase `main` branch:

```powershell
git clone https://github.com/Atimenka/FoxPhysic.git
cd FoxPhysic
git switch main
Expand-Archive -LiteralPath "C:\path\to\foxphys-0.1.1-beta-release-source.zip" -DestinationPath . -Force
git status
git add -A
git commit -m "Prepare FoxPhysic 0.1.1-beta-release"
git push origin main
```

If `main` is protected, push a feature branch and open a PR to `main`:

```powershell
git switch -c fox-physic
git add -A
git commit -m "Prepare FoxPhysic 0.1.1-beta-release"
git push -u origin fox-physic
gh pr create --base main --head fox-physic --title "FoxPhysic 0.1.1-beta-release" --body "Beta source candidate; Windows native runtime and live Minecraft acceptance still require verification."
```

Git branch names cannot contain spaces. The GitHub repository currently exposes lowercase `main`; use that spelling.
