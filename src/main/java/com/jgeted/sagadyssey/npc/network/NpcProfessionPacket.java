package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.core.network.NetworkRateLimiter;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.service.AuthorityScope;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
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
            Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_profession")
    );

    public static final StreamCodec<ByteBuf, NpcProfessionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeInt(packet.npcId);
                NetworkStrings.write(buf, packet.professionName, 32);
            },
            buf -> new NpcProfessionPacket(buf.readInt(), NetworkStrings.read(buf, 32))
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
            if (!NetworkRateLimiter.allow(player, "npc_profession", 4)) return;
            Entity entity = player.level().getEntity(data.npcId);

            if (!(entity instanceof NpcBase npc)) {
                player.displayClientMessage(
                        Component.literal("§c这个 NPC 已经不存在了"), true);
                return;
            }

            var principal = CharacterRegistry.currentId(player);
            if (!player.isAlive() || principal == null) {
                player.displayClientMessage(Component.literal("当前没有可用的在生人物，不能行使工作授权。"), true);
                return;
            }
            var verdict = ServiceRelationshipData.get(player.level().getServer()).authorize(
                    principal, npc.getCharacterId(),
                    AuthorityScope.ASSIGN_ROLE, false, player.level().getGameTime());
            if (!verdict.allowed()) {
                player.displayClientMessage(
                        Component.literal("§c没有岗位协商授权：" + verdict.reason()), true);
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

            for (int slot = 0; slot < npc.getTransportInventory().getContainerSize(); slot++) {
                if (!npc.getTransportInventory().getItem(slot).isEmpty()) {
                    player.displayClientMessage(
                            Component.literal("§c请先完成当前运输订单并卸空货舱，再切换职业"), true);
                    return;
                }
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

            // 转职不销毁财物：先卸入 36 格随身物品栏，极端满包时生成带归属标记的掉落物。
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND,
                    EquipmentSlot.OFFHAND}) {
                ItemStack equipped = npc.getItemBySlot(slot).copy();
                if (equipped.isEmpty()) continue;
                npc.setItemSlot(slot, ItemStack.EMPTY);
                if (!npc.addToBag(equipped)) npc.spawnWorkOutput(equipped);
            }

            LogisticsData.get(player.level().getServer()).releaseOrdersForNpc(
                    npc.getCharacterId(), player.level().getGameTime());
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
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.EMERALD)) {
                count += stack.getCount();
                if (count >= cost) return true;
            }
        }
        return false;
    }

    private static boolean deductEmeralds(ServerPlayer player, int cost) {
        int remaining = cost;
        for (int i = 0; i < player.getInventory().getNonEquipmentItems().size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(i);
            if (stack.is(Items.EMERALD) && stack.getCount() < stack.getMaxStackSize()) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.shrink(toRemove);
                remaining -= toRemove;
            }
        }
        for (int i = 0; i < player.getInventory().getNonEquipmentItems().size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(i);
            if (stack.is(Items.EMERALD)) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.shrink(toRemove);
                remaining -= toRemove;
            }
        }
        return remaining == 0;
    }
}
