package dev.foxphys.net;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Network payloads of FoxPhys (Fabric). Both are server to client.
 *
 * SoftMeshes: the current shape of every soft body, sent once per server tick.
 * FluidFrame: the particle positions of every SPH water pool near the player, sent once per server tick.
 */
public final class FoxPhysNetwork {
    private FoxPhysNetwork() {}

    private static Identifier id(String path) {
        return Identifier.of("foxphys", path);
    }

    /** One soft body: particle positions (x, y, z per particle, world space) and surface triangles. */
    /** AlignRequest (client to server): the R key was pressed in the gravity gun. */
    public record AlignRequest() implements CustomPayload {
        public static final CustomPayload.Id<AlignRequest> ID = new CustomPayload.Id<>(id("align_request"));
        public static final PacketCodec<RegistryByteBuf, AlignRequest> CODEC = PacketCodec.unit(new AlignRequest());

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record SoftMesh(int id, String preset, float[] positions, int[] triangles) {}

    public record SoftMeshes(List<SoftMesh> meshes) implements CustomPayload {
        public static final CustomPayload.Id<SoftMeshes> ID = new CustomPayload.Id<>(id("soft_meshes"));
        public static final PacketCodec<RegistryByteBuf, SoftMeshes> CODEC = PacketCodec.of(SoftMeshes::write, SoftMeshes::read);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        private static void write(SoftMeshes value, RegistryByteBuf buf) {
            buf.writeVarInt(value.meshes().size());
            for (SoftMesh m : value.meshes()) {
                buf.writeVarInt(m.id());
                buf.writeString(m.preset(), 32);
                buf.writeVarInt(m.positions().length);
                for (float f : m.positions()) buf.writeFloat(f);
                buf.writeVarInt(m.triangles().length);
                for (int t : m.triangles()) buf.writeVarInt(t);
            }
        }

        private static SoftMeshes read(RegistryByteBuf buf) {
            int count = buf.readVarInt();
            List<SoftMesh> meshes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int id = buf.readVarInt();
                String preset = buf.readString(32);
                float[] positions = new float[buf.readVarInt()];
                for (int k = 0; k < positions.length; k++) positions[k] = buf.readFloat();
                int[] triangles = new int[buf.readVarInt()];
                for (int k = 0; k < triangles.length; k++) triangles[k] = buf.readVarInt();
                meshes.add(new SoftMesh(id, preset, positions, triangles));
            }
            return new SoftMeshes(meshes);
        }
    }

    /** One SPH water pool: anchor corner (world), particle spacing, particle positions relative to the anchor. */
    public record FluidPoolFrame(int id, double x, double y, double z, float dx, float[] xyz) {}

    public record FluidFrame(List<FluidPoolFrame> pools) implements CustomPayload {
        public static final CustomPayload.Id<FluidFrame> ID = new CustomPayload.Id<>(id("fluid_frame"));
        public static final PacketCodec<RegistryByteBuf, FluidFrame> CODEC = PacketCodec.of(FluidFrame::write, FluidFrame::read);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        private static void write(FluidFrame value, RegistryByteBuf buf) {
            buf.writeVarInt(value.pools().size());
            for (FluidPoolFrame f : value.pools()) {
                buf.writeVarInt(f.id());
                buf.writeDouble(f.x());
                buf.writeDouble(f.y());
                buf.writeDouble(f.z());
                buf.writeFloat(f.dx());
                buf.writeVarInt(f.xyz().length);
                for (float v : f.xyz()) buf.writeFloat(v);
            }
        }

        private static FluidFrame read(RegistryByteBuf buf) {
            int count = buf.readVarInt();
            List<FluidPoolFrame> pools = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int id = buf.readVarInt();
                double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
                float dx = buf.readFloat();
                float[] xyz = new float[buf.readVarInt()];
                for (int k = 0; k < xyz.length; k++) xyz[k] = buf.readFloat();
                pools.add(new FluidPoolFrame(id, x, y, z, dx, xyz));
            }
            return new FluidFrame(pools);
        }
    }

    /** Registers the payload types. Called from the common initializer (server and client). */
    public static void registerTypes() {
        PayloadTypeRegistry.playS2C().register(SoftMeshes.ID, SoftMeshes.CODEC);
        PayloadTypeRegistry.playS2C().register(FluidFrame.ID, FluidFrame.CODEC);
        PayloadTypeRegistry.playC2S().register(AlignRequest.ID, AlignRequest.CODEC);
    }
}
