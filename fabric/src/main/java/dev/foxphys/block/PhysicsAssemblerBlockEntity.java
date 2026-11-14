package dev.foxphys.block;

import dev.foxphys.Assembler;
import dev.foxphys.FoxPhysBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.screen.NamedScreenHandlerFactory;

/** Stores the selected mode of one assembler. */
public final class PhysicsAssemblerBlockEntity extends BlockEntity implements NamedScreenHandlerFactory {
    private Assembler.Mode mode = Assembler.Mode.ASSEMBLE;

    public PhysicsAssemblerBlockEntity(BlockPos pos, BlockState state) {
        super(FoxPhysBlocks.ASSEMBLER_ENTITY, pos, state);
    }

    public Assembler.Mode mode() {
        return mode;
    }

    public void setMode(Assembler.Mode mode) {
        this.mode = mode;
        markDirty();
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        nbt.putString("mode", mode.id);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        try {
            mode = Assembler.Mode.parse(nbt.getString("mode"));
        } catch (IllegalArgumentException e) {
            mode = Assembler.Mode.ASSEMBLE;
        }
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("block.foxphys.physics_assembler");
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity player) {
        return new AssemblerScreenHandler(syncId, inventory, this);
    }
}
