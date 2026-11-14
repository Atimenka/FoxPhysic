import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Loads the JNI entry classes from a distributable mod jar only (no project/common classpath). */
public final class PackagedLoaderSmoke {
    private PackagedLoaderSmoke() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: PackagedLoaderSmoke <mod.jar>");
        URL artifact = Path.of(args[0]).toAbsolutePath().toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{artifact}, ClassLoader.getPlatformClassLoader())) {
            for (String name : new String[]{
                    "dev.foxphys.nativecore.NativeLoader",
                    "dev.foxphys.nativecore.NativeBridge"
            }) {
                Class<?> loaded = Class.forName(name, false, loader);
                if (loaded.getClassLoader() != loader) {
                    throw new AssertionError(name + " was not loaded from the tested mod jar");
                }
            }
        }
        System.out.println("PACKAGED_LOADER_SMOKE_OK: " + Path.of(args[0]).getFileName());
    }
}
