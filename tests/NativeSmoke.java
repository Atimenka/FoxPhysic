import dev.foxphys.nativecore.NativeBridge;

/** Plain JVM smoke test, no Minecraft classes or Gradle needed. Run with -ea. */
public final class NativeSmoke {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void expectError(Runnable action) {
        try { action.run(); throw new AssertionError("Expected IllegalStateException"); }
        catch (IllegalStateException expected) { /* JNI exception propagated safely. */ }
    }
    public static void main(String[] args) throws Exception {
        System.load(java.nio.file.Path.of(args[0]).toAbsolutePath().toString());
        require(NativeBridge.abiVersion() == 1, "ABI");
        long a = NativeBridge.create("{}");
        long b = NativeBridge.create("{}");
        require(a > 0 && b > a, "Unique handles");
        String body = "{\"dt\":0.05,\"commands\":[{\"op\":\"add_body\",\"body\":{\"id\":1,\"position\":[0,10,0],\"parts\":[{\"offset\":[0,0,0],\"size\":[1,1,1],\"density\":550}]}}]}";
        String state = NativeBridge.exchange(a, body);
        require(state.contains("\"id\":1"), "Body snapshot");
        require(state.contains("\"fixed_steps\":6"), "120Hz substeps");
        require(!NativeBridge.exchange(b, "{}").contains("\"id\":1"), "World isolation");
        expectError(() -> NativeBridge.exchange(a, "not json"));
        expectError(() -> NativeBridge.exchange(a, "{\"dt\":-1}"));
        require(NativeBridge.exchange(a, "{}").contains("\"id\":1"), "Still usable after input error");
        NativeBridge.destroy(a);
        expectError(() -> NativeBridge.exchange(a, "{}"));
        expectError(() -> NativeBridge.destroy(a));
        NativeBridge.destroy(b);

        // Physics assembler over JNI: 2x2x2 cells -> one body, attach, refused tilt, disassemble.
        long c = NativeBridge.create("{}");
        StringBuilder cells = new StringBuilder();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            if (cells.length() > 0) cells.append(',');
            cells.append("{\"cell\":[").append(x).append(',').append(10 + y).append(',').append(z)
                 .append("],\"density\":1000}");
        }
        String assemble = NativeBridge.exchange(c, "{\"commands\":[{\"op\":\"assemble_body\",\"id\":7,\"cells\":[" + cells + "]}]}");
        require(assemble.contains("\"ok\":true") && assemble.contains("assembled body 7"), "assemble: " + assemble);
        String attach = NativeBridge.exchange(c, "{\"commands\":[{\"op\":\"attach_cells\",\"id\":7,\"cells\":[{\"cell\":[2,10,0],\"density\":1000}]}]}");
        require(attach.contains("\"ok\":true"), "attach: " + attach);
        String detach = NativeBridge.exchange(c, "{\"commands\":[{\"op\":\"disassemble_body\",\"id\":7}]}");
        require(detach.contains("\"ok\":true") && detach.contains("\"cells\":[["), "disassemble: " + detach);
        require(!NativeBridge.exchange(c, "{}").contains("\"id\":7"), "disassembled body must be removed");
        // A plain body with a 45-degree yaw must be refused for attach (not grid-aligned).
        NativeBridge.exchange(c, "{\"commands\":[{\"op\":\"add_body\",\"body\":{\"id\":8,\"position\":[0.5,20.5,0.5],\"rotation\":[0,0.3826834,0,0.9238795],\"parts\":[{\"offset\":[0,0,0],\"size\":[1,1,1],\"density\":500}]}}]}");
        String tilted = NativeBridge.exchange(c, "{\"commands\":[{\"op\":\"attach_cells\",\"id\":8,\"cells\":[{\"cell\":[1,20,0],\"density\":500}]}]}");
        require(tilted.contains("\"ok\":false") && tilted.contains("grid-aligned"), "tilted refusal: " + tilted);
        String bad = NativeBridge.exchange(c, "{\"commands\":[{\"op\":\"assemble_body\",\"id\":9,\"cells\":[{\"cell\":[0,0,0],\"density\":0}]}]}");
        require(bad.contains("\"ok\":false") && !bad.contains("\"id\":9"), "command error must be non-fatal: " + bad);
        NativeBridge.destroy(c);
        // Physics worker over JNI: steps on its own thread, inputs are pushed, calls return snapshots.
        long w = NativeBridge.workerCreate("{}");
        NativeBridge.workerPush(w, "{\"commands\":[{\"op\":\"add_body\",\"body\":{\"id\":1,\"position\":[0,10,0],\"parts\":[{\"offset\":[0,0,0],\"size\":[1,1,1],\"density\":550}]}}]}");
        java.util.regex.Pattern yPattern = java.util.regex.Pattern.compile("\"position\":\\[[^,]+,([^,]+),");
        double y = Double.NaN;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            String latest = NativeBridge.workerLatest(w);
            if (latest != null && latest.contains("\"id\":1") && latest.contains("\"fixed_steps\":")) {
                java.util.regex.Matcher m = yPattern.matcher(latest);
                if (m.find()) y = Double.parseDouble(m.group(1));
                if (!Double.isNaN(y) && y < 9.9) break;
            }
            Thread.sleep(20);
        }
        require(y < 9.9, "worker must step and fall without any caller: y=" + y);
        String exported = NativeBridge.workerCall(w, "{\"commands\":[{\"op\":\"export_state\"}]}");
        require(exported.contains("\"format\":1") && exported.contains("\"ok\":true"), "export through worker: " + exported);
        expectError(() -> NativeBridge.workerCall(w, "not json"));
        // A rejected input is reported, not fatal: the worker keeps running.
        NativeBridge.workerPush(w, "{\"terrain\":[{\"center\":[0,0,0],\"size\":[0,1,1]}]}");
        String rejected = "";
        long errDeadline = System.nanoTime() + 3_000_000_000L;
        while (rejected.isEmpty() && System.nanoTime() < errDeadline) {
            rejected = NativeBridge.workerTakeError(w);
            if (rejected.isEmpty()) Thread.sleep(20);
        }
        require(rejected.startsWith("R:"), "rejected terrain must be reported as R:, got: " + rejected);
        require(NativeBridge.workerTakeError(w).isEmpty(), "error must be reported once");
        NativeBridge.workerSetIdle(w, true);
        NativeBridge.workerDestroy(w);
        expectError(() -> NativeBridge.workerDestroy(w));
        expectError(() -> NativeBridge.workerLatest(w));
        // Incremental terrain, exactly the JSON Lab.java sends: upsert, removal, duplicate refusal.
        long d = NativeBridge.create("{}");
        String plate = "{\"commands\":[{\"op\":\"patch_terrain\",\"add\":[{\"key\":5,\"center\":[0,-0.25,0],\"size\":[20,0.5,20]}],\"remove\":[]}]}";
        String patched = NativeBridge.exchange(d, plate);
        require(patched.contains("\"ok\":true") && patched.contains("\"static_colliders\":1"), "patch_terrain: " + patched);
        String removed = NativeBridge.exchange(d, "{\"commands\":[{\"op\":\"patch_terrain\",\"add\":[],\"remove\":[5,99]}]}");
        require(removed.contains("\"static_colliders\":0"), "removal (unknown key ignored): " + removed);
        String dup = NativeBridge.exchange(d, "{\"commands\":[{\"op\":\"patch_terrain\",\"add\":[{\"key\":1,\"center\":[0,0,0],\"size\":[1,1,1]},{\"key\":1,\"center\":[0,0,0],\"size\":[1,1,1]}],\"remove\":[]}]}");
        require(dup.contains("\"ok\":false") && dup.contains("duplicate"), "duplicate key must be refused: " + dup);
        NativeBridge.destroy(d);
        // Snapped disassembly: a body that fell 1.2 cm after assembly still returns its 8 cells.
        long e = NativeBridge.create("{}");
        StringBuilder grid = new StringBuilder();
        for (int gx = 0; gx < 2; gx++) for (int gy = 0; gy < 2; gy++) for (int gz = 0; gz < 2; gz++) {
            if (grid.length() > 0) grid.append(',');
            grid.append("{\"cell\":[").append(gx).append(',').append(10 + gy).append(',').append(gz).append("],\"density\":1000}");
        }
        String built = NativeBridge.exchange(e, "{\"commands\":[{\"op\":\"assemble_body\",\"id\":9,\"cells\":[" + grid + "]}]}");
        require(built.contains("\"ok\":true"), "assemble for snap test: " + built);
        String fell = NativeBridge.exchange(e, "{\"dt\":0.05,\"commands\":[]}");
        require(fell.contains("\"id\":9"), "body must survive a step");
        String snapped = NativeBridge.exchange(e, "{\"commands\":[{\"op\":\"disassemble_body\",\"id\":9,\"snap\":true}]}");
        require(snapped.contains("\"ok\":true") && snapped.contains("disassembled into 8 cells"), "snapped disassembly: " + snapped);
        require(!NativeBridge.exchange(e, "{}").contains("\"id\":9"), "disassembled body must be gone");
        NativeBridge.destroy(e);
        // Soft bodies over JNI: spawn, step, status, pin, stiffness, remove, JSON errors.
        String sp = NativeBridge.softExchange("{\"op\":\"spawn\",\"preset\":\"rubber\",\"origin\":[0,0.1,0],\"floor\":0}");
        require(sp.contains("\"id\":1") && sp.contains("\"particles\":125"), "soft spawn: " + sp);
        String st = NativeBridge.softExchange("{\"op\":\"step\",\"dt\":0.05,\"substeps\":8}");
        require(st.contains("\"bodies\":1"), "soft step: " + st);
        for (int i = 0; i < 100; i++) NativeBridge.softExchange("{\"op\":\"step\",\"dt\":0.05,\"substeps\":8}");
        String stat = NativeBridge.softExchange("{\"op\":\"status\",\"id\":1}");
        require(stat.contains("\"finite\":true"), "soft status finite: " + stat);
        require(NativeBridge.softExchange("{\"op\":\"pin\",\"id\":1,\"side\":\"top\"}").contains("\"pinned\":"), "soft pin");
        require(NativeBridge.softExchange("{\"op\":\"stiffness\",\"id\":1,\"value\":2.0}").contains("\"stiffness\":2.0"), "soft stiffness");
        require(NativeBridge.softExchange("{\"op\":\"remove\",\"id\":1}").contains("\"removed\":1"), "soft remove");
        expectError(() -> NativeBridge.softExchange("{\"op\":\"status\",\"id\":1}"));
        expectError(() -> NativeBridge.softExchange("not json"));
        System.out.println("JNI_SMOKE_OK: ABI, spawn/step, world isolation, JSON errors, lifecycle, assembler, incremental terrain, snapped disassembly, physics worker, soft bodies");
    }
}
