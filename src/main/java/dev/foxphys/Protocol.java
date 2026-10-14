package dev.foxphys;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

public final class Protocol {
    public static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
    private Protocol() {}
    public record Part(float[] offset, float[] size, float density, float friction, float restitution, String block) {}
    public record BodyState(long id, float[] position, float[] rotation, float[] velocity,
                            float[] angularVelocity, float mass, List<Part> parts,
                            float[] buoyancyForce, float[] airForce,
                            float[] aabbMin, float[] aabbMax) {}
    public record TankSpec(float[] origin, int nx, int nz, float dx, float depthLeft, float depthRight,
                           float viscosity, float damping) {}
    public record TankState(TankSpec spec, float[] depths, double volume, double relativeVolumeError) {}
    /** cells: lab-local block cells of a successful disassembly (null otherwise). */
    public record Result(boolean ok, String message, int[][] cells) {}
    /** Push-out of one actor (entity) after a fixed step. Correction and carry are lab-local. */
    public record ActorContact(long actor, float[] correction, float[] carry, boolean standing) {}
    public record Snapshot(int abi, double simSeconds, long fixedSteps, boolean paused,
                           List<BodyState> bodies, TankState tank, float[] wind,
                           long lastStepUs, int staticColliders, List<Result> results,
                           List<ActorContact> contacts, long contactStep) {}
    public static JsonArray vector(double... values) {
        JsonArray array = new JsonArray();
        for (double v : values) array.add(v);
        return array;
    }
    public static JsonArray indices(int... values) {
        JsonArray array = new JsonArray();
        for (int v : values) array.add(v);
        return array;
    }
    public static JsonObject op(String name) {
        JsonObject object = new JsonObject(); object.addProperty("op", name); return object;
    }
}
