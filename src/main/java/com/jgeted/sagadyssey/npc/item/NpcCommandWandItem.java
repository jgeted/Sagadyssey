package com.jgeted.sagadyssey.npc.item;

import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.npc.command.PlayerCommandData;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 指挥杖：右键 NPC 选择；右键地面两次框选；右键空气打开集团面板。
 * 蹲下 + 右键地面清空当前选择与区域。
 */
public class NpcCommandWandItem extends Item {
    private static final int MAX_SELECTION_SPAN = 128;

    public NpcCommandWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();

        if (!context.getLevel().isClientSide && player instanceof ServerPlayer serverPlayer) {
            PlayerCommandData data = CommandGroupAttachments.get(serverPlayer);
            if (player.isShiftKeyDown()) {
                data.clearSelection();
                player.displayClientMessage(Component.literal("§e已清空选择与区域"), true);
            } else if (data.corner1() == null || data.corner2() != null) {
                data.setCorner1(pos);
                player.displayClientMessage(Component.literal("§a第一角 " + pos.toShortString()
                        + "；请在对角处再次右键"), true);
                cornerEffect(serverPlayer.serverLevel(), pos);
            } else {
                BlockPos a = data.corner1();
                if (Math.abs(a.getX() - pos.getX()) > MAX_SELECTION_SPAN
                        || Math.abs(a.getZ() - pos.getZ()) > MAX_SELECTION_SPAN) {
                    player.displayClientMessage(Component.literal("§c框选边长不能超过 128 格，请重新选择第二角"), true);
                    return InteractionResult.SUCCESS;
                }
                data.setCorner2(pos);
                data.clearSelectedMembers();
                int minX = Math.min(a.getX(), pos.getX());
                int maxX = Math.max(a.getX(), pos.getX());
                int minZ = Math.min(a.getZ(), pos.getZ());
                int maxZ = Math.max(a.getZ(), pos.getZ());
                AABB selection = new AABB(minX, player.getY() - 32, minZ,
                        maxX + 1, player.getY() + 32, maxZ + 1);
                int count = 0;
                for (NpcBase npc : serverPlayer.serverLevel().getEntitiesOfClass(NpcBase.class, selection,
                        npc -> npc.isAlive() && npc.isOwnedBy(player.getUUID()))) {
            data.registerMember(npc.getCharacterId(), npc.getNpcName());
            if (data.hasMember(npc.getCharacterId())) {
                data.select(npc.getCharacterId());
                        count++;
                    }
                }
                player.displayClientMessage(Component.literal("§a框选完成：已选中 " + count
                        + " 名 NPC；右键空气打开集团面板"), true);
                cornerEffect(serverPlayer.serverLevel(), pos);
            }
            CommandGroupAttachments.sync(serverPlayer, false);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            CommandGroupAttachments.sync(serverPlayer, true);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    private static void cornerEffect(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.END_ROD,
                pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5,
                12, 0.35, 0.35, 0.35, 0.02);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§b右键 NPC：选择 / 取消选择"));
        tooltip.add(Component.literal("§b右键地面两次：区域框选"));
        tooltip.add(Component.literal("§e右键空气：打开集团面板"));
        tooltip.add(Component.literal("§7蹲下右键地面：清空选择与区域"));
    }
}
