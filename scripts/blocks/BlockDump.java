import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class BlockDump {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        StringBuilder out = new StringBuilder();
        out.append("id\thardness\tblast\tsound\tfull_cube\tfluid\tblock_entity\ttool_required\tmap_color\tsolid\tair\tclass\n");
        for (Block b : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(b).toString();
            try {
                BlockState s = b.defaultBlockState();
                float hard = s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                float blast = b.getExplosionResistance();
                String sound = s.getSoundType().getBreakSound().getLocation().toString();
                boolean full = s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                boolean fluid = !s.getFluidState().isEmpty();
                boolean be = s.hasBlockEntity();
                boolean tool = s.requiresCorrectToolForDrops();
                int mc = s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id;
                boolean solid = s.isSolid();
                boolean air = s.isAir();
                out.append(id).append('\t').append(hard).append('\t').append(blast).append('\t').append(sound)
                   .append('\t').append(full).append('\t').append(fluid).append('\t').append(be)
                   .append('\t').append(tool).append('\t').append(mc).append('\t').append(solid)
                   .append('\t').append(air).append('\t').append(b.getClass().getName()).append('\n');
            } catch (Throwable t) {
                out.append(id).append("\tERROR\t").append(t).append('\n');
            }
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), out.toString());
        System.out.println("blocks: " + BuiltInRegistries.BLOCK.size());
    }
}
