package dev.foxphys.block;

import dev.foxphys.Assembler;
import dev.foxphys.FoxPhysMod;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

/**
 * Server-side panel: a 3-row chest whose item slots are buttons. Clicks on the button slots run an
 * action; nothing can be taken or put into them. The vanilla client shows it as an ordinary chest.
 */
public final class AssemblerScreenHandler extends ChestMenu {
    static final int SLOT_ASSEMBLE = 10, SLOT_ATTACH = 11, SLOT_DISASSEMBLE = 12, SLOT_INFO = 13, SLOT_RUN = 16;
    private final PhysicsAssemblerBlockEntity assembler;

    public AssemblerScreenHandler(int syncId, Inventory playerInventory, PhysicsAssemblerBlockEntity assembler) {
        super(MenuType.GENERIC_9x3, syncId, playerInventory, buttons(assembler), 3);
        this.assembler = assembler;
    }

    private static SimpleContainer buttons(PhysicsAssemblerBlockEntity assembler) {
        SimpleContainer inv = new SimpleContainer(27);
        inv.setItem(SLOT_ASSEMBLE, named(Items.IRON_BLOCK, "Собрать блоки в тело"));
        inv.setItem(SLOT_ATTACH, named(Items.SLIME_BLOCK, "Прикрепить блоки к телу рядом"));
        inv.setItem(SLOT_DISASSEMBLE, named(Items.IRON_PICKAXE, "Разобрать тело рядом"));
        inv.setItem(SLOT_RUN, named(Items.LIME_DYE, "Запустить выбранный режим"));
        fillInfo(inv, assembler);
        return inv;
    }

    private static void fillInfo(SimpleContainer inv, PhysicsAssemblerBlockEntity assembler) {
        ItemStack info = named(Items.PAPER, "Режим: " + assembler.mode().title + " (скан 32×32×32)");
        inv.setItem(SLOT_INFO, info);
    }

    private static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType actionType, Player player) {
        if (slotIndex >= 0 && slotIndex < 27) {
            if (player instanceof ServerPlayer sp) act(slotIndex, sp);
            broadcastChanges();
            return;
        }
        super.clicked(slotIndex, button, actionType, player);
    }

    private void act(int slot, ServerPlayer player) {
        try {
            switch (slot) {
                case SLOT_ASSEMBLE -> assembler.setMode(Assembler.Mode.ASSEMBLE);
                case SLOT_ATTACH -> assembler.setMode(Assembler.Mode.ATTACH);
                case SLOT_DISASSEMBLE -> assembler.setMode(Assembler.Mode.DISASSEMBLE);
                case SLOT_RUN -> {
                    String message = Assembler.run((net.minecraft.server.level.ServerLevel) assembler.getLevel(), assembler.getBlockPos(), assembler.mode());
                    player.displayClientMessage(Component.literal(message), true);
                }
                default -> { return; }
            }
            if (slot != SLOT_RUN) {
                if (getContainer() instanceof SimpleContainer inv) fillInfo(inv, assembler);
                player.displayClientMessage(Component.literal("Режим: " + assembler.mode().title), true);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            player.displayClientMessage(Component.literal("FoxPhys: " + e.getMessage()), true);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        // Shift-click must not move items into or out of the button slots.
        return slot < 27 ? ItemStack.EMPTY : super.quickMoveStack(player, slot);
    }
}
