package dev.foxphys.nativecore;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Loaded lazily on the server: remote clients do not run a physics kernel. */
public final class NativeLoader {
    private static boolean loaded;
    private NativeLoader() {}
    public static synchronized void ensureLoaded() {
        if (loaded) return;
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            throw new IllegalStateException("FoxPhys alpha supports x64 only; detected " + arch);
        }
        String override = System.getProperty("foxphys.nativePath");
        try {
            if (override != null && !override.isBlank()) {
                System.load(Path.of(override).toAbsolutePath().toString());
            } else {
                String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                String platform;
                String file;
                if (os.contains("win")) { platform = "windows-x86_64"; file = "foxphys_core.dll"; }
                else if (os.contains("linux")) { platform = "linux-x86_64"; file = "libfoxphys_core.so"; }
                else throw new IllegalStateException("Unsupported OS: " + os);
                String resource = "/natives/" + platform + "/" + file;
                try (InputStream input = NativeLoader.class.getResourceAsStream(resource)) {
                    if (input == null) throw new IllegalStateException("Missing Rust library " + resource +
                            ". Use a platform build, or build with Rust; -PskipNative is development-only.");
                    Path folder = Files.createTempDirectory("foxphys-native-");
                    Path target = folder.resolve(file);
                    folder.toFile().deleteOnExit();
                    Files.copy(input, target);
                    target.toFile().deleteOnExit();
                    System.load(target.toAbsolutePath().toString());
                }
            }
            if (NativeBridge.abiVersion() != NativeBridge.ABI) {
                throw new IllegalStateException("FoxPhys JNI ABI mismatch: Java=" + NativeBridge.ABI +
                        ", Rust=" + NativeBridge.abiVersion());
            }
            loaded = true;
        } catch (IOException | UnsatisfiedLinkError e) {
            throw new IllegalStateException("Cannot load FoxPhys Rust kernel: " + e.getMessage(), e);
        }
    }
}
