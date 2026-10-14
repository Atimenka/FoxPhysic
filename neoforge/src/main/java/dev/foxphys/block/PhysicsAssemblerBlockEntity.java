package dev.foxphys.block;

import dev.foxphys.Assembler;
import dev.foxphys.FoxPhysBlocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.MenuProvider;

/** Stores the selected mode of one assembler. */
public final class PhysicsAssemblerBlockEntity extends BlockEntity implements MenuProvider {
    private Assembler.Mode mode = Assembler.Mode.ASSEMBLE;

    public PhysicsAssemblerBlockEntity(BlockPos pos, BlockState state) {
        super(FoxPhysBlocks.ASSEMBLER_ENTITY.get(), pos, state);
    }

    public Assembler.Mode mode() {
        return mode;
    }

    public void setMode(Assembler.Mode mode) {
        this.mode = mode;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        nbt.putString("mode", mode.id);
    }

    @Override
    protected void loadAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);
        try {
            mode = Assembler.Mode.parse(nbt.getString("mode"));
        } catch (IllegalArgumentException e) {
            mode = Assembler.Mode.ASSEMBLE;
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.foxphys.physics_assembler");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
        return new AssemblerScreenHandler(syncId, inventory, this);
    }
}
