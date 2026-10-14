package dev.foxphys;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The physics assembler's three modes and its 32 x 32 x 32 scanner.
 *
 * ASSEMBLE: blocks connected to the assembler (6-connectivity, inside the scanner) become one body.
 * ATTACH: the same connected blocks join the nearest body within 4 blocks.
 * DISASSEMBLE: the nearest body within 4 blocks returns its blocks to the world.
 * The physics world is created on first use next to the assembler; there is no fixed region.
 */
public final class Assembler {
    public enum Mode {
        ASSEMBLE("assemble", "Сборка"), ATTACH("attach", "Прикрепление"), DISASSEMBLE("disassemble", "Разборка");
        public final String id, title;
        Mode(String id, String title) { this.id = id; this.title = title; }
        public static Mode parse(String text) {
            for (Mode m : values()) if (m.id.equals(text.toLowerCase(Locale.ROOT))) return m;
            throw new IllegalArgumentException("Режимы: assemble, attach, disassemble");
        }
    }
    /** Scanner side length in blocks (the scanner is a cube centred on the assembler). */
    public static final int SCAN = 32;
    /** Most blocks converted by one run. */
    public static final int MAX_CELLS = 512;
    /** Largest cube side for /foxphys assemble: 16^3 = 4096 cells, the Rust per-command cap. */
    public static final int MAX_ASSEMBLE_SIDE = 16;
    /**
     * Most blocks that may be physical in one world at once (all bodies of that dimension). This is
     * what keeps glue and the assembler from turning the terrain into bodies when max_bodies is 0.
     */
    public static final int MAX_PHYSICAL_CELLS = 8192;
    /** Most blocks in one assemble run: the Rust per-command cap (4096). */
    public static final int MAX_ASSEMBLE_CELLS = 4096;
    /** Body search radius for attach and disassemble, in blocks. */
    public static final double BODY_RADIUS = 4.0;

    private Assembler() {}

    /** Runs one mode on the assembler at `pos`. Returns a message; throws IllegalArgumentException on refusal. */
    public static String run(ServerLevel world, BlockPos pos, Mode mode) {
        Lab lab = FoxPhysMod.manager().ensure(world, pos);
        String message = switch (mode) {
            case ASSEMBLE -> {
                List<BlockPos> cells = scan(lab, pos);
                if (cells.isEmpty()) throw new IllegalArgumentException("Рядом с сборщиком нет блоков для сборки");
                yield lab.assembleCells(cells);
            }
            case ATTACH -> {
                long body = nearestBody(lab, pos);
                List<BlockPos> cells = scan(lab, pos);
                if (cells.isEmpty()) throw new IllegalArgumentException("Рядом с сборщиком нет блоков для прикрепления");
                yield lab.attachCells(body, cells);
            }
            case DISASSEMBLE -> {
                yield lab.disassemble(nearestBody(lab, pos));
            }
        };
        FoxPhysMod.manager().persistNow(world); // every mode changes assemblies
        return message;
    }

    private static long nearestBody(Lab lab, BlockPos pos) {
        var body = lab.bodyNear(Vec3.atCenterOf(pos), BODY_RADIUS);
        if (body == null) throw new IllegalArgumentException("Нет тела в радиусе 4 блоков");
        return body.id();
    }

    /**
     * Honey glue: flood fill (6-connectivity) from the clicked block, the same connectivity the
     * assembler uses. Unlike the assembler it REFUSES instead of truncating: a partial group would
     * leave part of a structure behind, and a group that reaches the scanner edge is usually terrain.
     */
    public static String glue(ServerLevel world, BlockPos start) {
        Lab lab = FoxPhysMod.manager().ensure(world, start);
        if (!lab.convertible(start)) throw new IllegalArgumentException("Этот блок нельзя склеить");
        int half = SCAN / 2;
        List<BlockPos> group = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        seen.add(start);
        queue.add(start);
        group.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                BlockPos n = p.relative(d);
                if (!seen.add(n)) continue;
                if (!lab.convertible(n)) continue;
                if (Math.abs(n.getX() - start.getX()) > half || Math.abs(n.getY() - start.getY()) > half || Math.abs(n.getZ() - start.getZ()) > half)
                    throw new IllegalArgumentException("Связанная группа выходит за 32 блока: клей склеивает только отдельный кусок");
                group.add(n);
                if (group.size() > MAX_CELLS)
                    throw new IllegalArgumentException("Группа больше " + MAX_CELLS + " блоков: клей не берёт землю и целые постройки");
                queue.add(n);
            }
        }
        String message = lab.assembleCells(group);
        FoxPhysMod.manager().persistNow(world);
        return message;
    }

    /** Blocks connected to the assembler inside the scanner. */
    static List<BlockPos> scan(Lab lab, BlockPos pos) {
        int half = SCAN / 2;
        List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        seen.add(pos);
        queue.add(pos);
        while (!queue.isEmpty() && out.size() < MAX_CELLS) {
            BlockPos p = queue.poll();
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                BlockPos n = p.relative(d);
                if (!seen.add(n)) continue;
                if (Math.abs(n.getX() - pos.getX()) > half || Math.abs(n.getY() - pos.getY()) > half || Math.abs(n.getZ() - pos.getZ()) > half) continue;
                if (!lab.convertible(n)) continue;
                out.add(n);
                queue.add(n);
                if (out.size() >= MAX_CELLS) break;
            }
        }
        return out;
    }
}
