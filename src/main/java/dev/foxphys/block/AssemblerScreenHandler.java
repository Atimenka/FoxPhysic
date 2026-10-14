package dev.foxphys.block;

import dev.foxphys.Assembler;
import dev.foxphys.FoxPhysMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Server-side panel: a 3-row chest whose item slots are buttons. Clicks on the button slots run an
 * action; nothing can be taken or put into them. The vanilla client shows it as an ordinary chest.
 */
public final class AssemblerScreenHandler extends GenericContainerScreenHandler {
    static final int SLOT_ASSEMBLE = 10, SLOT_ATTACH = 11, SLOT_DISASSEMBLE = 12, SLOT_INFO = 13, SLOT_RUN = 16;
    private final PhysicsAssemblerBlockEntity assembler;

    public AssemblerScreenHandler(int syncId, PlayerInventory playerInventory, PhysicsAssemblerBlockEntity assembler) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, buttons(assembler), 3);
        this.assembler = assembler;
    }

    private static SimpleInventory buttons(PhysicsAssemblerBlockEntity assembler) {
        SimpleInventory inv = new SimpleInventory(27);
        inv.setStack(SLOT_ASSEMBLE, named(Items.IRON_BLOCK, "Собрать блоки в тело"));
        inv.setStack(SLOT_ATTACH, named(Items.SLIME_BLOCK, "Прикрепить блоки к телу рядом"));
        inv.setStack(SLOT_DISASSEMBLE, named(Items.IRON_PICKAXE, "Разобрать тело рядом"));
        inv.setStack(SLOT_RUN, named(Items.LIME_DYE, "Запустить выбранный режим"));
        fillInfo(inv, assembler);
        return inv;
    }

    private static void fillInfo(SimpleInventory inv, PhysicsAssemblerBlockEntity assembler) {
        ItemStack info = named(Items.PAPER, "Режим: " + assembler.mode().title + " (скан 32×32×32)");
        inv.setStack(SLOT_INFO, info);
    }

    private static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex < 27) {
            if (player instanceof ServerPlayerEntity sp) act(slotIndex, sp);
            sendContentUpdates();
            return;
        }
        super.onSlotClick(slotIndex, button, actionType, player);
    }

    private void act(int slot, ServerPlayerEntity player) {
        try {
            switch (slot) {
                case SLOT_ASSEMBLE -> assembler.setMode(Assembler.Mode.ASSEMBLE);
                case SLOT_ATTACH -> assembler.setMode(Assembler.Mode.ATTACH);
                case SLOT_DISASSEMBLE -> assembler.setMode(Assembler.Mode.DISASSEMBLE);
                case SLOT_RUN -> {
                    String message = Assembler.run((net.minecraft.server.world.ServerWorld) assembler.getWorld(), assembler.getPos(), assembler.mode());
                    player.sendMessage(Text.literal(message), true);
                }
                default -> { return; }
            }
            if (slot != SLOT_RUN) {
                if (getInventory() instanceof SimpleInventory inv) fillInfo(inv, assembler);
                player.sendMessage(Text.literal("Режим: " + assembler.mode().title), true);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            player.sendMessage(Text.literal("FoxPhys: " + e.getMessage()), true);
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        // Shift-click must not move items into or out of the button slots.
        return slot < 27 ? ItemStack.EMPTY : super.quickMove(player, slot);
    }
}
