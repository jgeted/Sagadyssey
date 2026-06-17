package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：请求切换 NPC 职业。
 */
public record NpcProfessionPacket(int npcId, String professionName) implements CustomPacketPayload {

    public static final Type<NpcProfessionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_profession")
    );

    public static final StreamCodec<ByteBuf, NpcProfessionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeInt(packet.npcId);
                byte[] bytes = packet.professionName.getBytes();
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
            },
            buf -> {
                int npcId = buf.readInt();
                int len = buf.readInt();
                byte[] bytes = new byte[len];
                buf.readBytes(bytes);
                return new NpcProfessionPacket(npcId, new String(bytes));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static final int SWITCH_COST = 3;

    /**
     * 服务端处理入口。
     */
    public static void handle(final NpcProfessionPacket data, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            Entity entity = player.serverLevel().getEntity(data.npcId);

            if (!(entity instanceof NpcBase npc)) {
                player.displayClientMessage(
                        Component.literal("§c这个 NPC 已经不存在了"), true);
                return;
            }

            if (!npc.isOwnedBy(player.getUUID())) {
                player.displayClientMessage(
                        Component.literal("§c这个 NPC 不属于你"), true);
                return;
            }

            if (player.distanceToSqr(npc) > 64.0D) {
                player.displayClientMessage(
                        Component.literal("§c你离 NPC 太远了"), true);
                return;
            }

            NpcProfession newProfession;
            try {
                newProfession = NpcProfession.valueOf(data.professionName);
            } catch (IllegalArgumentException e) {
                player.displayClientMessage(
                        Component.literal("§c无效的职业名称"), true);
                return;
            }

            if (npc.getProfession() == newProfession) {
                player.displayClientMessage(
                        Component.literal("§e这个 NPC 已经是该职业了"), true);
                return;
            }

            if (!hasEnoughEmeralds(player, SWITCH_COST)) {
                player.displayClientMessage(
                        Component.literal("§c绿宝石不足！需要 " + SWITCH_COST + " 个绿宝石"), true);
                return;
            }

            if (!deductEmeralds(player, SWITCH_COST)) {
                player.displayClientMessage(
                        Component.literal("§c扣除绿宝石失败"), true);
                return;
            }

            // 转职降一级：计算降级后经验
            int currentLevel = npc.getNpcLevel();
            int penaltyExp = switch (currentLevel) {
                case 2 -> 0;
                case 3 -> 200;
                case 4 -> 600;
                default -> 0;
            };

            // 清除装备栏（保留背包）
            npc.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            npc.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            npc.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            npc.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            npc.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            npc.setBowSlot(ItemStack.EMPTY);
            npc.setArrowSlot(ItemStack.EMPTY);

            npc.setProfession(newProfession);
            npc.applyInitialEquipment(newProfession);
            npc.setExperience(penaltyExp);  // 应用降级经验

            player.displayClientMessage(
                    Component.literal("§a成功将 " + npc.getNpcName() + " 切换为 "
                            + newProfession.getDisplayName() + "！花费 " + SWITCH_COST + " 绿宝石"), false);
        });
    }

    private static boolean hasEnoughEmeralds(ServerPlayer player, int cost) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.EMERALD)) {
                count += stack.getCount();
                if (count >= cost) return true;
            }
        }
        return false;
    }

    private static boolean deductEmeralds(ServerPlayer player, int cost) {
        int remaining = cost;
        for (int i = 0; i < player.getInventory().items.size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (stack.is(Items.EMERALD) && stack.getCount() < stack.getMaxStackSize()) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.shrink(toRemove);
                remaining -= toRemove;
            }
        }
        for (int i = 0; i < player.getInventory().items.size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (stack.is(Items.EMERALD)) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.shrink(toRemove);
                remaining -= toRemove;
            }
        }
        return remaining == 0;
    }
}
