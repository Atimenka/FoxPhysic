package dev.foxphys.net;

import dev.foxphys.FoxPhysMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Network payloads of FoxPhys. Registered on the mod bus.
 *
 * AlignRequest (client to server): the R key was pressed in the gravity gun.
 * SoftMeshes (server to client): the current shape of every soft body, sent once per server tick.
 */
public final class FoxPhysNetwork {
    private FoxPhysNetwork() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("foxphys", path);
    }

    public record AlignRequest() implements CustomPacketPayload {
        public static final Type<AlignRequest> TYPE = new Type<>(id("align_request"));
        public static final StreamCodec<FriendlyByteBuf, AlignRequest> CODEC = StreamCodec.unit(new AlignRequest());

        @Override
        public Type<AlignRequest> type() {
            return TYPE;
        }
    }

    /** One soft body: particle positions (x, y, z per particle, world space) and surface triangles. */
    public record SoftMesh(int id, String preset, float[] positions, int[] triangles) {}

    public record SoftMeshes(List<SoftMesh> meshes) implements CustomPacketPayload {
        public static final Type<SoftMeshes> TYPE = new Type<>(id("soft_meshes"));
        public static final StreamCodec<FriendlyByteBuf, SoftMeshes> CODEC =
                StreamCodec.of(SoftMeshes::write, SoftMeshes::read);

        @Override
        public Type<SoftMeshes> type() {
            return TYPE;
        }

        private static void write(FriendlyByteBuf buf, SoftMeshes value) {
            buf.writeVarInt(value.meshes().size());
            for (SoftMesh m : value.meshes()) {
                buf.writeVarInt(m.id());
                buf.writeUtf(m.preset(), 32);
                buf.writeVarInt(m.positions().length);
                for (float f : m.positions()) buf.writeFloat(f);
                buf.writeVarInt(m.triangles().length);
                for (int t : m.triangles()) buf.writeVarInt(t);
            }
        }

        private static SoftMeshes read(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            List<SoftMesh> meshes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int id = buf.readVarInt();
                String preset = buf.readUtf(32);
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

    /** Every water pool near the receiving player, sent once per server tick. */
    public record FluidFrame(List<FluidPoolFrame> pools) implements CustomPacketPayload {
        public static final Type<FluidFrame> TYPE = new Type<>(id("fluid_frame"));
        public static final StreamCodec<FriendlyByteBuf, FluidFrame> CODEC =
                StreamCodec.of(FluidFrame::write, FluidFrame::read);

        @Override
        public Type<FluidFrame> type() {
            return TYPE;
        }

        private static void write(FriendlyByteBuf buf, FluidFrame value) {
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

        private static FluidFrame read(FriendlyByteBuf buf) {
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

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(AlignRequest.TYPE, AlignRequest.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) FoxPhysMod.interactions().align(player);
                }));
        registrar.playToClient(SoftMeshes.TYPE, SoftMeshes.CODEC, (payload, context) ->
                context.enqueueWork(() -> dev.foxphys.client.SoftBodyRenderer.receive(payload)));
        registrar.playToClient(FluidFrame.TYPE, FluidFrame.CODEC, (payload, context) ->
                context.enqueueWork(() -> dev.foxphys.client.FluidRenderer.receive(payload)));
    }

    /** Sends the soft-body shapes to every connected player. */
    public static void sendSoftMeshes(List<SoftMesh> meshes) {
        PacketDistributor.sendToAllPlayers(new SoftMeshes(meshes));
    }

    /** Sends the water pools near one player. */
    public static void sendFluid(net.minecraft.server.level.ServerPlayer player, FluidFrame frame) {
        PacketDistributor.sendToPlayer(player, frame);
    }
}
