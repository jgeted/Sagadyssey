package com.jgeted.sagadyssey.npc.command;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcEmploymentContract;
import com.jgeted.sagadyssey.social.life.NpcWorkShift;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.life.NpcScheduleRecord;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.service.AuthorityScope;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 用指挥杖的当前选择管理生活设施、排班、工作地点和显式外派合同。 */
public final class NpcLifeCommand {
    private static final double BLOCK_REACH = 20.0D;

    private NpcLifeCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga").then(Commands.literal("npc")
                .then(Commands.literal("assign")
                        .then(Commands.literal("bed").executes(ctx -> assignBed(ctx.getSource())))
                        .then(Commands.literal("storage").executes(ctx -> assignStorage(ctx.getSource())))
                        .then(Commands.literal("workplace")
                                .executes(ctx -> assignWorkplace(ctx.getSource(), false))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> assignWorkplace(ctx.getSource(), true)))))
                .then(Commands.literal("shift")
                        .then(Commands.literal("day").executes(ctx -> shift(ctx.getSource(), NpcWorkShift.DAY)))
                        .then(Commands.literal("night").executes(ctx -> shift(ctx.getSource(), NpcWorkShift.NIGHT)))
                        .then(Commands.literal("flexible").executes(ctx -> shift(ctx.getSource(), NpcWorkShift.FLEXIBLE))))
                .then(Commands.literal("contract")
                        .then(Commands.literal("nearest")
                                .executes(ctx -> contract(ctx.getSource(),
                                        NpcEmploymentContract.DEFAULT_DURATION_DAYS,
                                        NpcEmploymentContract.DEFAULT_WAGE))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 30))
                                        .then(Commands.argument("wage", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> contract(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "days"),
                                                        IntegerArgumentType.getInteger(ctx, "wage"))))))
                        .then(Commands.literal("renew")
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 30))
                                        .executes(ctx -> renewContract(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "days")))))
                        .then(Commands.literal("status").executes(ctx -> contractStatus(ctx.getSource())))
                        .then(Commands.literal("clear").executes(ctx -> clearContract(ctx.getSource()))))
                .then(Commands.literal("needs")
                        .then(Commands.literal("hunger")
                                .requires(source -> MinecraftApi.hasPermission(source, 2))
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 1000))
                                        .executes(ctx -> setHunger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "value"))))))
                .then(Commands.literal("schedule")
                        .executes(ctx -> scheduleStatus(ctx.getSource())))));
    }

    private static int assignBed(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        NpcBase npc = oneSelected(player, source);
        if (npc == null) return 0;
        BlockPos pos = lookedBlock(player);
        if (pos == null || !player.level().getBlockState(pos).is(BlockTags.BEDS)) {
            source.sendFailure(Component.literal("请看向二十格以内的一张床。"));
            return 0;
        }
        var bedState = player.level().getBlockState(pos);
        if (bedState.hasProperty(BedBlock.PART) && bedState.getValue(BedBlock.PART) == BedPart.HEAD) {
            pos = pos.relative(bedState.getValue(BedBlock.FACING).getOpposite());
        }
        BlockPos assignedBed = pos;
        String dimension = player.level().dimension().identifier().toString();
        SettlementRecord settlement = SettlementRegistryData.get(source.getServer())
                .nearest(dimension, pos, 128.0D);
        String name = settlement == null ? "玩家分配住宅" : settlement.name() + "玩家分配住宅";
        if (!NpcLifeData.get(source.getServer()).assignBed(npc.getCharacterId(), dimension, assignedBed,
                name, player.level().getGameTime())) {
            source.sendFailure(Component.literal("床位已被其他 NPC 使用，或该 NPC 尚无生活档案。"));
            return 0;
        }
        mark(player, assignedBed);
        source.sendSuccess(() -> Component.literal("§a已把 " + assignedBed.toShortString()
                + " 设为 " + npc.getNpcName() + " 的私人床。"), false);
        return 1;
    }

    private static int assignStorage(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        NpcBase npc = oneSelected(player, source);
        if (npc == null) return 0;
        BlockPos pos = lookedBlock(player);
        String dimension = player.level().dimension().identifier().toString();
        if (pos == null || WarehouseContainers.asContainer(player.level().getBlockEntity(pos)) == null) {
            source.sendFailure(Component.literal("请看向二十格以内的箱子或木桶。"));
            return 0;
        }
        var storageState = player.level().getBlockState(pos);
        if (storageState.hasProperty(ChestBlock.TYPE)
                && storageState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            source.sendFailure(Component.literal("私人储物请使用单箱或木桶，避免双箱两半被重复分配。"));
            return 0;
        }
        if (LogisticsData.get(source.getServer()).warehouseAt(dimension, pos) != null) {
            source.sendFailure(Component.literal("公共仓库不能同时作为私人储物；请另放一个箱子或木桶。"));
            return 0;
        }
        if (!NpcLifeData.get(source.getServer()).assignPrivateStorage(source.getServer(),
                npc.getCharacterId(), dimension,
                pos, player.level().getGameTime())) {
            source.sendFailure(Component.literal("容器已分配给其他 NPC，或该 NPC 尚无生活档案。"));
            return 0;
        }
        mark(player, pos);
        source.sendSuccess(() -> Component.literal("§a已把 " + pos.toShortString()
                + " 设为 " + npc.getNpcName() + " 的私人储物。"), false);
        return 1;
    }

    private static int assignWorkplace(CommandSourceStack source, boolean clear)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<NpcBase> selected = selectedOwned(player);
        if (selected.isEmpty()) return noSelection(source);
        BlockPos pos = clear ? null : lookedBlock(player);
        if (!clear && pos == null) {
            source.sendFailure(Component.literal("请看向二十格以内的工作方块或工作区中心。"));
            return 0;
        }
        UUID characterId = CharacterRegistry.ensureCurrent(player).id();
        NpcLifeData data = NpcLifeData.get(source.getServer());
        int changed = 0;
        for (NpcBase npc : selected) {
            if (!authorized(player, npc, AuthorityScope.ASSIGN_WORKPLACE)) continue;
            if (!data.assignEmployerWorkplace(npc.getCharacterId(), characterId, pos,
                    player.level().getGameTime())) continue;
            if (clear) {
                data.assignEmployerWorkZone(npc, characterId, null, null);
            } else {
                switch (npc.getProfession()) {
                    case FARMER -> {
                        BlockPos ground = player.level().getBlockState(pos).is(Blocks.FARMLAND)
                                ? pos : player.level().getBlockState(pos.below()).is(Blocks.FARMLAND)
                                ? pos.below() : pos;
                        data.assignEmployerWorkZone(npc, characterId,
                                ground.offset(-4, 0, -4), ground.offset(4, 1, 4));
                    }
                    case WORKER -> data.assignEmployerWorkZone(npc, characterId,
                            pos.offset(-8, -3, -8), pos.offset(8, 5, 8));
                    case TRADER -> data.assignEmployerWorkZone(npc, characterId,
                            pos.offset(-12, 0, -12), pos.offset(12, 0, 12));
                    case WARRIOR, ARCHER, HEAVY -> npc.setPatrol(pos, 16);
                    default -> { }
                }
            }
            changed++;
        }
        if (pos != null) mark(player, pos);
        int result = changed;
        source.sendSuccess(() -> Component.literal("§a已为 " + result + " 名 NPC "
                + (clear ? "清除工作地点。" : "设置工作地点 " + pos.toShortString() + "。")), false);
        return changed;
    }

    private static int shift(CommandSourceStack source, NpcWorkShift shift)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<NpcBase> selected = selectedOwned(player);
        if (selected.isEmpty()) return noSelection(source);
        NpcLifeData data = NpcLifeData.get(source.getServer());
        int changed = 0;
        for (NpcBase npc : selected) {
            if (!authorized(player, npc, AuthorityScope.ASSIGN_SCHEDULE)) continue;
            if (data.setShift(npc.getCharacterId(), shift,
                    player.level().getGameTime())) changed++;
        }
        int result = changed;
        source.sendSuccess(() -> Component.literal("§a已把 " + result + " 名 NPC 的排班设为 "
                + shift.name() + "。"), false);
        return changed;
    }

    private static int contract(CommandSourceStack source, int days, int wage)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<NpcBase> selected = selectedOwned(player);
        if (selected.isEmpty()) return noSelection(source);
        String dimension = player.level().dimension().identifier().toString();
        SettlementRecord settlement = SettlementRegistryData.get(source.getServer())
                .nearest(dimension, player.blockPosition(), 128.0D);
        if (settlement == null) {
            source.sendFailure(Component.literal("附近 128 格没有已登记聚落。"));
            return 0;
        }
        UUID characterId = CharacterRegistry.ensureCurrent(player).id();
        boolean authorized = MinecraftApi.hasPermission(source, 2)
                || characterId.equals(settlement.founderCharacterId())
                || PoliticalStateData.get(source.getServer()).canGrantTitle(
                settlement.factionId(), settlement.id(), characterId);
        if (!authorized) {
            source.sendFailure(Component.literal("你不是该聚落的建立者、领主或阵营统治者，不能签发外派合同。"));
            return 0;
        }
        NpcLifeData data = NpcLifeData.get(source.getServer());
        int changed = 0;
        ServiceRelationshipData services = ServiceRelationshipData.get(source.getServer());
        for (NpcBase npc : selected) {
            if (!data.beginContract(npc.getCharacterId(), characterId,
                    settlement.id(), settlement.center(), days, wage,
                    player.level().getGameTime())) continue;
            services.beginWageContract(data.contract(npc.getCharacterId()), dimension);
            changed++;
        }
        int result = changed;
        source.sendSuccess(() -> Component.literal("§a已为 " + result + " 名 NPC 签订 "
                + settlement.name() + " 外派合同：期限 " + days + " 日、日薪 " + wage
                + " 枚绿宝石。工资将从该聚落实体仓库转入 NPC 私人储物。"), false);
        return changed;
    }

    private static int renewContract(CommandSourceStack source, int days)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<NpcBase> selected = selectedOwned(player);
        if (selected.isEmpty()) return noSelection(source);
        UUID characterId = CharacterRegistry.ensureCurrent(player).id();
        NpcLifeData data = NpcLifeData.get(source.getServer());
        int changed = 0;
        ServiceRelationshipData services = ServiceRelationshipData.get(source.getServer());
        for (NpcBase npc : selected) {
            if (!data.renewContract(npc.getCharacterId(), characterId,
                    days, player.level().getGameTime())) continue;
            services.syncContract(data.contract(npc.getCharacterId()),
                    player.level().getGameTime());
            changed++;
        }
        int result = changed;
        source.sendSuccess(() -> Component.literal("§a已为 " + result
                + " 名 NPC 续期 " + days + " 个游戏日；已有欠薪不会被清除。"), false);
        return changed;
    }

    private static int contractStatus(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<NpcBase> selected = selectedOwned(player);
        if (selected.isEmpty()) return noSelection(source);
        NpcLifeData data = NpcLifeData.get(source.getServer());
        long now = player.level().getGameTime();
        int shown = 0;
        for (NpcBase npc : selected) {
            NpcEmploymentContract contract = data.contract(npc.getCharacterId());
            if (contract == null) {
                source.sendSuccess(() -> Component.literal("§7" + npc.getNpcName() + "：没有合同记录"), false);
                continue;
            }
            SettlementRecord settlement = SettlementRegistryData.get(source.getServer())
                    .byId(contract.settlementId());
            String settlementName = settlement == null ? contract.settlementId().toString() : settlement.name();
            long remainingTicks = Math.max(0L, contract.endsAt() - now);
            long remainingDays = (remainingTicks + 23_999L) / 24_000L;
            source.sendSuccess(() -> Component.literal("§e" + npc.getNpcName() + "："
                    + contract.status().displayName() + "，雇佣地=" + settlementName
                    + "，日薪=" + contract.wageEmeralds() + "，欠薪=" + contract.unpaidEmeralds()
                    + "，剩余约=" + remainingDays + "日；" + contract.statusReason()), false);
            shown++;
        }
        return shown;
    }

    private static int clearContract(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<NpcBase> selected = selectedOwned(player);
        if (selected.isEmpty()) return noSelection(source);
        UUID characterId = CharacterRegistry.ensureCurrent(player).id();
        NpcLifeData data = NpcLifeData.get(source.getServer());
        int changed = 0;
        ServiceRelationshipData services = ServiceRelationshipData.get(source.getServer());
        for (NpcBase npc : selected) {
            if (!data.endContract(npc.getCharacterId(), characterId,
                    player.level().getGameTime())) continue;
            services.syncContract(data.contract(npc.getCharacterId()),
                    player.level().getGameTime());
            CommandGroupAttachments.unregisterNpc(player, npc);
            npc.dismiss();
            changed++;
        }
        int result = changed;
        if (result == 0) {
            source.sendFailure(Component.literal("没有可解除的合同；到期工资或欠薪必须先由聚落实体仓库结清。"));
        } else {
            source.sendSuccess(() -> Component.literal("§a已结束 " + result
                    + " 名 NPC 的外派合同；服务授权终止并恢复原机构岗位。"), false);
        }
        return changed;
    }

    private static int setHunger(CommandSourceStack source, int value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<NpcBase> selected = selectedOwned(player);
        if (selected.isEmpty()) {
            NpcBase looked = lookedAtNpc(player);
            if (looked != null) selected.add(looked);
        }
        if (selected.isEmpty()) {
            source.sendFailure(Component.literal("请用指挥杖选中自己的 NPC，或直接看向二十格内的 NPC。"));
            return 0;
        }
        NpcLifeData data = NpcLifeData.get(source.getServer());
        selected.forEach(npc -> data.setHunger(npc.getCharacterId(), value,
                player.level().getGameTime()));
        source.sendSuccess(() -> Component.literal("§a已把 " + selected.size()
                + " 名 NPC 的饥饿值设为 " + value + "/1000。"), false);
        return selected.size();
    }

    private static int scheduleStatus(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<NpcBase> selected = selectedOwned(player);
        NpcBase npc = selected.size() == 1 ? selected.getFirst() : lookedAtNpc(player);
        if (npc == null) {
            source.sendFailure(Component.literal("请恰好选中一名自己的 NPC，或直接看向二十格内的 NPC。"));
            return 0;
        }
        NpcLifeData data = NpcLifeData.get(source.getServer());
        var life = data.record(npc.getCharacterId());
        if (life != null) NpcSchedulePlanner.refresh(npc, data, player.level());
        NpcScheduleRecord schedule = NpcSchedulePlanner.current(npc);
        source.sendSuccess(() -> Component.literal("§6[日程] §e" + npc.getNpcName()
                + " §f" + schedule.activity().displayName()), false);
        source.sendSuccess(() -> Component.literal("§7原因=" + schedule.reason()
                + "；排班=" + (life == null ? "未登记" : life.shift().name())
                + "；命令=" + npc.getCommand().name() + "；下次复核≤"
                + Math.max(0L, schedule.reviewAt() - player.level().getGameTime())
                + " tick"), false);
        return 1;
    }

    @Nullable
    private static NpcBase oneSelected(ServerPlayer player, CommandSourceStack source) {
        List<NpcBase> selected = selectedOwned(player);
        if (selected.size() != 1) {
            source.sendFailure(Component.literal("此操作需要用指挥杖恰好选中一名已加载 NPC；当前="
                    + selected.size() + "。"));
            return null;
        }
        return selected.getFirst();
    }

    private static List<NpcBase> selectedOwned(ServerPlayer player) {
        List<NpcBase> result = new ArrayList<>();
        for (UUID id : CommandGroupAttachments.get(player).selectedMembers()) {
            NpcBase npc = CharacterRegistryData.get(player.level().getServer()).findLoadedNpc(player.level().getServer(), id);
            if (npc != null && npc.isAlive() && npc.isOwnedBy(player.getUUID())) {
                result.add(npc);
            }
        }
        return result;
    }

    @Nullable
    private static BlockPos lookedBlock(ServerPlayer player) {
        HitResult hit = player.pick(BLOCK_REACH, 1.0F, false);
        return hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit
                ? blockHit.getBlockPos().immutable() : null;
    }

    @Nullable
    private static NpcBase lookedAtNpc(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getViewVector(1.0F);
        Vec3 end = eye.add(direction.scale(BLOCK_REACH));
        AABB search = player.getBoundingBox().expandTowards(
                direction.scale(BLOCK_REACH)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, search,
                entity -> entity instanceof NpcBase && entity.isPickable(),
                BLOCK_REACH * BLOCK_REACH);
        return hit != null && hit.getEntity() instanceof NpcBase npc ? npc : null;
    }

    private static int noSelection(CommandSourceStack source) {
        source.sendFailure(Component.literal("请先用指挥杖选中至少一名属于你的、当前已加载的 NPC。"));
        return 0;
    }

    private static boolean authorized(ServerPlayer player, NpcBase npc, AuthorityScope scope) {
        UUID principal = CharacterRegistry.currentId(player);
        if (!player.isAlive() || principal == null) return false;
        return ServiceRelationshipData.get(player.level().getServer()).authorize(principal,
                npc.getCharacterId(), scope, false,
                player.level().getGameTime()).allowed();
    }

    private static void mark(ServerPlayer player, BlockPos pos) {
        player.level().sendParticles(player, ParticleTypes.END_ROD, true, false,
                pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                18, 0.35D, 0.45D, 0.35D, 0.02D);
    }
}
