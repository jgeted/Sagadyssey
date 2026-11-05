package com.jgeted.sagadyssey.npc.container;

import com.jgeted.sagadyssey.core.registry.ModMenuTypes;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * NPC 装备菜单。
 * 槽位布局：
 *   0-3 护甲, 4 主手, 5 副手
 *   6-41 NPC 随身物品栏 (36 格，与玩家一致)
 *   42-68 玩家主背包, 69-77 玩家快捷栏
 */
public class NpcEquipMenu extends AbstractContainerMenu {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final NpcBase npc;
    private final ContainerLevelAccess access;

    /** 装备槽 → EquipmentSlot 映射 */
    private static final EquipmentSlot[] EQUIP_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND
    };

    // === 客户端构造函数（MenuType 工厂调用） ===
    public NpcEquipMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, () -> {
            var e = playerInventory.player.level().getEntity(buf.readInt());
            if (e instanceof NpcBase npc) return npc;
            return null;
        });
    }

    // === 服务端构造函数（MenuProvider 调用） ===
    public NpcEquipMenu(int containerId, Inventory playerInventory, NpcBase npc) {
        this(containerId, playerInventory, () -> npc);
    }

    private NpcEquipMenu(int containerId, Inventory playerInventory, java.util.function.Supplier<NpcBase> npcSupplier) {
        super(ModMenuTypes.NPC_EQUIP.get(), containerId);
        this.npc = npcSupplier.get();
        if (this.npc == null) {
            this.access = null;
            return;
        }
        this.access = ContainerLevelAccess.create(npc.level(), npc.blockPosition());

        // NPC 装备槽适配器：将 Container 操作映射到实体装备
        Container equipContainer = new EquipContainer(npc);

        // === NPC 装备槽 (0-5) ===
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            addSlot(new Slot(equipContainer, idx, getX(idx), getY(idx)) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return stack.canEquip(EQUIP_SLOTS[idx], npc);
                }
                @Override
                public int getMaxStackSize() { return 1; }
            });
        }

        // === NPC 随身物品栏 (6-41): 4×9 ===
        Container bag = npc.getPersonalInventory();
        int bagIdx = 0;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(bag, bagIdx++, 8 + col * 18, 82 + row * 18));
            }
        }

        // === 玩家主背包 (42-68): 3×9 ===
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 176 + row * 18));
            }
        }

        // === 玩家快捷栏 (69-77): 1×9 ===
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 234));
        }
    }

    private static int getX(int equipIdx) {
        return switch (equipIdx) {
            case 0 -> 8;   // head
            case 1 -> 26;  // chest
            case 2 -> 8;   // legs
            case 3 -> 26;  // feet
            case 4 -> 80;  // mainhand
            case 5 -> 98;  // offhand
            default -> 0;
        };
    }

    private static int getY(int equipIdx) {
        return switch (equipIdx) {
            case 0 -> 38; // head
            case 1 -> 38; // chest
            case 2 -> 56; // legs
            case 3 -> 56; // feet
            case 4 -> 38; // mainhand
            case 5 -> 38; // offhand
            default -> 0;
        };
    }

    @Override
    public boolean stillValid(Player player) {
        return npc.isAlive() && player.distanceTo(npc) <= 8.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index < 6) {
            if (!this.moveItemStackTo(stack, 42, 78, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < 42) {
            if (!this.moveItemStackTo(stack, 42, 78, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (isArmorItem(stack)) {
                EquipmentSlot armorSlot = stack.getEquipmentSlot();
                if (armorSlot != null) {
                    int armorIdx = switch (armorSlot) {
                        case HEAD -> 0;
                        case CHEST -> 1;
                        case LEGS -> 2;
                        case FEET -> 3;
                        default -> -1;
                    };
                    if (armorIdx >= 0 && !this.slots.get(armorIdx).hasItem()) {
                        if (!this.moveItemStackTo(stack, armorIdx, armorIdx + 1, false)) {
                            moveToBagOrSwap(stack);
                        }
                    } else {
                        moveToBagOrSwap(stack);
                    }
                } else {
                    moveToBagOrSwap(stack);
                }
            } else {
                moveToBagOrSwap(stack);
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        if (stack.getCount() == copy.getCount()) {
            return ItemStack.EMPTY;
        }

        slot.onTake(player, stack);
        return copy;
    }

    private void moveToBagOrSwap(ItemStack stack) {
        if (!this.moveItemStackTo(stack, 6, 42, false)) {
            this.moveItemStackTo(stack, 42, 78, false);
        }
    }

    private boolean isArmorItem(ItemStack stack) {
        EquipmentSlot slot = stack.getEquipmentSlot();
        return slot != null
                && slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR
                && slot != EquipmentSlot.BODY;
    }

    // === 装备槽 ← → 实体装备 适配器 ===

    private static class EquipContainer implements Container {
        private final NpcBase npc;

        EquipContainer(NpcBase npc) {
            this.npc = npc;
        }

        @Override
        public int getContainerSize() { return 6; }

        @Override
        public boolean isEmpty() {
            for (EquipmentSlot es : EQUIP_SLOTS) {
                if (!npc.getItemBySlot(es).isEmpty()) return false;
            }
            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            return npc.getItemBySlot(EQUIP_SLOTS[index]);
        }

        @Override
        public ItemStack removeItem(int index, int amount) {
            ItemStack current = npc.getItemBySlot(EQUIP_SLOTS[index]).copy();
            if (current.isEmpty()) return ItemStack.EMPTY;
            ItemStack split = current.split(amount);
            npc.setItemSlot(EQUIP_SLOTS[index], current.isEmpty() ? ItemStack.EMPTY : current);
            return split;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            ItemStack current = npc.getItemBySlot(EQUIP_SLOTS[index]).copy();
            npc.setItemSlot(EQUIP_SLOTS[index], ItemStack.EMPTY);
            return current;
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            LOGGER.debug("EquipContainer.setItem: index={}, item={}", index,
                    stack.isEmpty() ? "EMPTY" : stack.getItem().getDescriptionId());
            npc.setItemSlot(EQUIP_SLOTS[index], stack);
        }

        @Override
        public void setChanged() {}

        @Override
        public boolean stillValid(Player player) { return npc.isAlive(); }

        @Override
        public void clearContent() {
            for (EquipmentSlot es : EQUIP_SLOTS) {
                npc.setItemSlot(es, ItemStack.EMPTY);
            }
        }
    }
}
