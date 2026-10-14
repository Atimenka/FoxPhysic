import dev.foxphys.Protocol;
import dev.foxphys.nativecore.NativeBridge;
import dev.foxphys.nativecore.NativeLoader;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Exercises the same Gson records and loader used by the Fabric integration. */
public final class ProtocolSmoke {
    public static void main(String[] args) {
        System.setProperty("foxphys.nativePath", java.nio.file.Path.of(args[0]).toAbsolutePath().toString());
        NativeLoader.ensureLoaded(); NativeLoader.ensureLoaded();
        long handle = NativeBridge.create("{}");
        try {
            var spec = new Protocol.TankSpec(new float[]{2,4,2},12,12,1,3,0.35f,0.001f,0.04f);
            var command = Protocol.op("set_tank"); command.add("tank",Protocol.GSON.toJsonTree(spec));
            JsonObject request = new JsonObject(); JsonArray list = new JsonArray(); list.add(command);
            request.add("commands",list); request.addProperty("dt",0.05f);
            var waterCell = new JsonObject(); waterCell.add("cell",Protocol.indices(0,0,0)); waterCell.addProperty("height",1.0f);
            var waterCells = new JsonArray(); waterCells.add(waterCell); request.add("water_cells",waterCells);
            var state = Protocol.GSON.fromJson(NativeBridge.exchange(handle,request.toString()),Protocol.Snapshot.class);
            if(state.abi()!=1 || state.fixedSteps()!=6 || !state.results().getFirst().ok()) throw new AssertionError("Snapshot/command");
            if(state.tank().depths().length!=144 || state.tank().spec().depthLeft()!=3 || Math.abs(state.tank().relativeVolumeError())>1e-9) throw new AssertionError("Tank record fields");
            var pause = Protocol.op("pause"); pause.addProperty("paused",true);
            list=new JsonArray();list.add(pause);request=new JsonObject();request.add("commands",list);
            state=Protocol.GSON.fromJson(NativeBridge.exchange(handle,request.toString()),Protocol.Snapshot.class);
            if(!state.paused()) throw new AssertionError("Pause field");
            // Actors, grabs and contacts cross JNI and parse into the Java records (ABI 1 field names).
            long world = NativeBridge.create("{}");
            try {
                var body = new JsonObject(); body.addProperty("id", 1); body.add("position", Protocol.vector(0, 0.5, 0));
                var parts = new JsonArray(); var part = new JsonObject();
                part.add("offset", Protocol.vector(0, 0, 0)); part.add("size", Protocol.vector(1, 1, 1));
                part.addProperty("density", 2600); part.addProperty("block", "minecraft:stone"); parts.add(part); body.add("parts", parts);
                var add = Protocol.op("add_body"); add.add("body", body);
                var floor = new JsonObject(); floor.add("center", Protocol.vector(0, -0.5, 0)); floor.add("size", Protocol.vector(20, 1, 20));
                var terrain = new JsonArray(); terrain.add(floor);
                var setup = new JsonObject(); setup.add("terrain", terrain);
                JsonArray cmds = new JsonArray(); cmds.add(add); setup.add("commands", cmds);
                NativeBridge.exchange(world, setup.toString());
                var actor = new JsonObject(); actor.addProperty("key", 7); actor.add("center", Protocol.vector(0, 1.85, 0));
                actor.add("half", Protocol.vector(0.3, 0.9, 0.3)); actor.addProperty("mass", 80);
                var actors = new JsonArray(); actors.add(actor);
                var grab = new JsonObject(); grab.addProperty("id", 1); grab.add("point", Protocol.vector(0, 0, 0));
                grab.add("target", Protocol.vector(0, 3, 0));
                var grabs = new JsonArray(); grabs.add(grab);
                var tick = new JsonObject(); tick.addProperty("dt", 1.0 / 120); tick.add("actors", actors); tick.add("grabs", grabs);
                var snap = Protocol.GSON.fromJson(NativeBridge.exchange(world, tick.toString()), Protocol.Snapshot.class);
                if (snap.contacts() == null || snap.contacts().size() != 1) throw new AssertionError("contacts missing: " + snap.contacts());
                var c = snap.contacts().getFirst();
                if (c.actor() != 7 || !c.standing() || c.correction()[1] <= 0 || snap.contactStep() <= 0) throw new AssertionError("contact fields " + c);
                var stale = Protocol.GSON.fromJson(NativeBridge.exchange(world, "{\"dt\":0}"), Protocol.Snapshot.class);
                if (stale.contactStep() != snap.contactStep() + 0 && stale.contactStep() < snap.contactStep()) throw new AssertionError("contact step went back");
                var bad = new JsonObject(); bad.add("actors", new JsonArray()); var dup = new JsonArray();
                var zero = new JsonObject(); zero.addProperty("key", 0); zero.add("center", Protocol.vector(0,0,0)); zero.add("half", Protocol.vector(0.3,0.9,0.3)); dup.add(zero);
                bad.add("actors", dup);
                try { NativeBridge.exchange(world, bad.toString()); throw new AssertionError("zero actor key accepted"); }
                catch (IllegalStateException expected) { /* rejected as required */ }
            } finally { NativeBridge.destroy(world); }
            System.out.println("PROTOCOL_SMOKE_OK: Gson snake_case records, native loader, tank, pause, actors, grabs, contacts");
        } finally { NativeBridge.destroy(handle); }
    }
}
