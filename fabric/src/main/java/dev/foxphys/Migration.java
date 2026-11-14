package dev.foxphys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Миграция с VoxPhys на FoxPhys.
 *
 * Правила: только КОПИРОВАНИЕ, никогда не удаление и не перезапись.
 * Файл копируется, если по новому пути его ещё нет. Старые файлы остаются на месте.
 */
public final class Migration {
    private Migration() {}

    static final String MESSAGE = "VoxPhys переименован в FoxPhys. Конфиги и сохранения мигрированы. Старые файлы сохранены.";
    private static boolean announced;

    /** The migration message is printed once per run, however many files were copied. */
    private static synchronized void announce() {
        if (announced) return;
        announced = true;
        FoxPhysMod.LOGGER.info(MESSAGE);
    }

    /** config/voxphys.json -> config/foxphys.json (если нового ещё нет). */
    public static int migrateConfig(Path configDir) {
        Path oldFile = configDir.resolve("voxphys.json");
        Path newFile = configDir.resolve("foxphys.json");
        if (!Files.isRegularFile(oldFile) || Files.exists(newFile)) return 0;
        try {
            Files.createDirectories(configDir);
            Files.copy(oldFile, newFile);
            announce();
            return 1;
        } catch (IOException e) {
            FoxPhysMod.LOGGER.error("FoxPhys: не удалось скопировать {} -> {}", oldFile, newFile, e);
            return 0;
        }
    }

    /** <мир>/voxphys/*.json -> <мир>/foxphys/*.json, поштучно, без перезаписи. */
    public static int migrateSaves(Path worldRoot) {
        Path oldDir = worldRoot.resolve("voxphys");
        Path newDir = worldRoot.resolve("foxphys");
        if (!Files.isDirectory(oldDir)) return 0;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(oldDir)) {
            s.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            FoxPhysMod.LOGGER.error("FoxPhys: не удалось прочитать {}", oldDir, e);
            return 0;
        }
        int copied = 0;
        for (Path src : files) {
            Path dst = newDir.resolve(oldDir.relativize(src).toString());
            if (Files.exists(dst)) continue;
            try {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst);
                copied++;
            } catch (IOException e) {
                FoxPhysMod.LOGGER.error("FoxPhys: не удалось скопировать {} -> {}", src, dst, e);
            }
        }
        if (copied > 0) announce();
        return copied;
    }
}
