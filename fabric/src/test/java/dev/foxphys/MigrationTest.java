package dev.foxphys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {

    @Test
    void configIsCopiedAndOldFileKept(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("voxphys.json"), "{\"a\":1}");
        assertEquals(1, Migration.migrateConfig(dir));
        assertEquals("{\"a\":1}", Files.readString(dir.resolve("foxphys.json")));
        assertTrue(Files.exists(dir.resolve("voxphys.json")), "old config must stay");
    }

    @Test
    void existingNewConfigIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("voxphys.json"), "old");
        Files.writeString(dir.resolve("foxphys.json"), "new");
        assertEquals(0, Migration.migrateConfig(dir));
        assertEquals("new", Files.readString(dir.resolve("foxphys.json")));
    }

    @Test
    void missingOldConfigIsNoop(@TempDir Path dir) {
        assertEquals(0, Migration.migrateConfig(dir));
        assertFalse(Files.exists(dir.resolve("foxphys.json")));
    }

    @Test
    void savesAreCopiedRecursivelyAndOldKept(@TempDir Path world) throws Exception {
        Path old = world.resolve("voxphys");
        Files.createDirectories(old.resolve("sub"));
        Files.writeString(old.resolve("minecraft_overworld.json"), "{\"format\":1}");
        Files.writeString(old.resolve("sub/extra.json"), "{}");
        assertEquals(2, Migration.migrateSaves(world));
        assertEquals("{\"format\":1}", Files.readString(world.resolve("foxphys/minecraft_overworld.json")));
        assertTrue(Files.exists(world.resolve("foxphys/sub/extra.json")));
        assertTrue(Files.exists(old.resolve("minecraft_overworld.json")), "old saves must stay");
    }

    @Test
    void savesNeverOverwriteNewFiles(@TempDir Path world) throws Exception {
        Files.createDirectories(world.resolve("voxphys"));
        Files.createDirectories(world.resolve("foxphys"));
        Files.writeString(world.resolve("voxphys/a.json"), "old");
        Files.writeString(world.resolve("foxphys/a.json"), "new");
        Files.writeString(world.resolve("voxphys/b.json"), "old-b");
        assertEquals(1, Migration.migrateSaves(world));
        assertEquals("new", Files.readString(world.resolve("foxphys/a.json")));
        assertEquals("old-b", Files.readString(world.resolve("foxphys/b.json")));
    }

    @Test
    void secondRunCopiesNothing(@TempDir Path world) throws Exception {
        Files.createDirectories(world.resolve("voxphys"));
        Files.writeString(world.resolve("voxphys/a.json"), "x");
        assertEquals(1, Migration.migrateSaves(world));
        assertEquals(0, Migration.migrateSaves(world));
    }
}
