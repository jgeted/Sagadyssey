package com.jgeted.sagadyssey.social.household;

import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRecord;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import com.jgeted.sagadyssey.social.life.NpcRelationKind;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 家庭管理只改变户籍和共享财产，不改变 NPC 的职业、集团或战斗命令。 */
public final class HouseholdCommand {
    private static final double REACH = 20.0D;

    private HouseholdCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga").then(Commands.literal("household")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> create(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("add")
                        .then(Commands.literal("spouse").executes(ctx -> add(ctx.getSource(), HouseholdRole.SPOUSE)))
                        .then(Commands.literal("child").executes(ctx -> add(ctx.getSource(), HouseholdRole.CHILD)))
                        .then(Commands.literal("kin").executes(ctx -> add(ctx.getSource(), HouseholdRole.KIN)))
                        .then(Commands.literal("dependent").executes(ctx -> add(ctx.getSource(), HouseholdRole.DEPENDENT)))
                        .then(Commands.literal("servant").executes(ctx -> add(ctx.getSource(), HouseholdRole.SERVANT)))
                        .then(Commands.literal("lodger").executes(ctx -> add(ctx.getSource(), HouseholdRole.LODGER))))
                .then(Commands.literal("remove").executes(ctx -> remove(ctx.getSource())))
                .then(Commands.literal("head").executes(ctx -> setHead(ctx.getSource())))
                .then(Commands.literal("storage")
                        .executes(ctx -> storage(ctx.getSource(), false))
                        .then(Commands.literal("clear").executes(ctx -> storage(ctx.getSource(), true))))
                .then(Commands.literal("inspect").executes(ctx -> inspect(ctx.getSource())))
                .then(Commands.literal("disband").then(Commands.literal("confirm")
                        .executes(ctx -> disband(ctx.getSource()))))));
    }

    private static int create(CommandSourceStack source, String name)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CharacterRecord character = CharacterRegistry.ensureCurrent(player);
        HouseholdData data = HouseholdData.get(source.getServer());
        if (data.forMember(character.id()) != null) {
            source.sendFailure(Component.literal("你已经属于一个家庭；不能同时登记在两个家庭。"));
            return 0;
        }
        String dimension = player.serverLevel().dimension().location().toString();
        SettlementRecord settlement = SettlementRegistryData.get(source.getServer())
                .nearest(dimension, player.blockPosition(), 128.0D);
        HouseholdRecord household = data.create(name, dimension,
                settlement == null ? null : settlement.id(), character.id(),
                player.blockPosition(), player.serverLevel().getGameTime());
        if (household == null) return 0;
        WorldHistoryData.get(source.getServer()).append(player.serverLevel().getGameTime(),
                "household_founded", household.id(), household.name(), character.id().toString(), 12);
        source.sendSuccess(() -> Component.literal("§a已创建家庭“" + household.name()
                + "”。这不会自动创建工作集团或战斗小队。"), false);
        return 1;
    }

    private static int add(CommandSourceStack source, HouseholdRole role)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CharacterRecord character = CharacterRegistry.ensureCurrent(player);
        HouseholdRecord household = manageableHousehold(source, character.id());
        if (household == null) return 0;
        List<NpcBase> selected = selectedOwned(player);
        if (selected.isEmpty()) return noSelection(source);
        HouseholdData data = HouseholdData.get(source.getServer());
        NpcLifeData lives = NpcLifeData.get(source.getServer());
        int changed = 0;
        for (NpcBase npc : selected) {
            if (!data.addMember(household.id(), npc.getCharacterId(), role,
                    player.serverLevel().getGameTime())) continue;
            NpcRelationKind relationKind = relationKind(role);
            int strength = relationStrength(role);
            lives.adjustRelationship(npc.getCharacterId(), household.headCharacterId(), relationKind,
                    strength, "共同生活于“" + household.name() + "”");
            if (relationKind == NpcRelationKind.FAMILY) {
                for (HouseholdMemberRecord member : household.members()) {
                    if (!member.characterId().equals(household.headCharacterId())
                            && !member.characterId().equals(npc.getCharacterId())) {
                        lives.linkRelationship(npc.getCharacterId(), member.characterId(),
                                NpcRelationKind.FAMILY, Math.max(35, strength - 15),
                                "同属“" + household.name() + "”");
                    }
                }
            }
            changed++;
        }
        int result = changed;
        source.sendSuccess(() -> Component.literal("§a已将 " + result + " 名 NPC 以“"
                + role.displayName() + "”身份加入家庭；其工作与小队设置没有改变。"), false);
        return changed;
    }

    private static int remove(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CharacterRecord character = CharacterRegistry.ensureCurrent(player);
        HouseholdRecord household = manageableHousehold(source, character.id());
        if (household == null) return 0;
        List<NpcBase> selected = selectedOwned(player);
        if (selected.isEmpty()) return noSelection(source);
        HouseholdData data = HouseholdData.get(source.getServer());
        int changed = 0;
        for (NpcBase npc : selected) if (data.removeMember(household.id(), npc.getCharacterId())) changed++;
        int result = changed;
        source.sendSuccess(() -> Component.literal("§e已从家庭移除 " + result
                + " 名成员；历史关系与记忆不会被抹除。"), false);
        return changed;
    }

    private static int setHead(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CharacterRecord character = CharacterRegistry.ensureCurrent(player);
        HouseholdRecord household = manageableHousehold(source, character.id());
        if (household == null) return 0;
        List<NpcBase> selected = selectedOwned(player);
        if (selected.size() != 1 || !household.contains(selected.getFirst().getCharacterId())) {
            source.sendFailure(Component.literal("请恰好选中一名已属于该家庭的 NPC。"));
            return 0;
        }
        NpcBase npc = selected.getFirst();
        if (!HouseholdData.get(source.getServer()).setHead(household.id(), npc.getCharacterId(),
                player.serverLevel().getGameTime())) return 0;
        source.sendSuccess(() -> Component.literal("§a" + npc.getNpcName()
                + " 已成为新户主；原户主变为亲属。"), false);
        return 1;
    }

    private static int storage(CommandSourceStack source, boolean clear)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CharacterRecord character = CharacterRegistry.ensureCurrent(player);
        HouseholdRecord household = manageableHousehold(source, character.id());
        if (household == null) return 0;
        BlockPos pos = clear ? null : lookedBlock(player);
        String dimension = player.serverLevel().dimension().location().toString();
        if (!clear && (pos == null
                || WarehouseContainers.asContainer(player.serverLevel().getBlockEntity(pos)) == null)) {
            source.sendFailure(Component.literal("请看向二十格以内的单箱、木桶或潜影盒。"));
            return 0;
        }
        if (!clear) {
            var state = player.serverLevel().getBlockState(pos);
            if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                source.sendFailure(Component.literal("家庭共享储物请使用单箱，避免双箱两半重复登记。"));
                return 0;
            }
            if (LogisticsData.get(source.getServer()).warehouseAt(dimension, pos) != null
                    || NpcLifeData.get(source.getServer()).privateStorageAssigned(pos, dimension)) {
                source.sendFailure(Component.literal("公共仓库或 NPC 私人箱不能同时登记为家庭共享财产。"));
                return 0;
            }
        }
        BlockPos anchor = clear ? household.homeAnchor() : pos;
        if (!HouseholdData.get(source.getServer()).assignSharedStorage(household.id(), dimension,
                anchor, pos)) {
            source.sendFailure(Component.literal("容器已属于另一个家庭，或与家庭维度不一致。"));
            return 0;
        }
        if (pos != null) mark(player, pos);
        source.sendSuccess(() -> Component.literal(clear ? "§e已取消家庭共享储物登记。"
                : "§a已登记家庭共享储物 " + pos.toShortString()
                + "；它不会获得公共仓库或工作订单权限。"), false);
        return 1;
    }

    private static int inspect(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        HouseholdData data = HouseholdData.get(source.getServer());
        NpcBase target = lookedAtNpc(player);
        var current = CharacterRegistry.currentId(player);
        if (target == null && current == null) {
            source.sendFailure(Component.literal("当前没有已绑定人物；查询不会创建人物。"));
            return 0;
        }
        HouseholdRecord household = target == null ? data.forMember(current) : data.forMember(target.getUUID());
        if (household == null) {
            source.sendFailure(Component.literal(target == null ? "你尚未登记家庭。"
                    : target.getNpcName() + " 尚未登记家庭。"));
            return 0;
        }
        CharacterRegistryData characters = CharacterRegistryData.get(source.getServer());
        source.sendSuccess(() -> Component.literal("§6[家庭] §e" + household.name()
                + " §7ID=" + household.id()), false);
        source.sendSuccess(() -> Component.literal("§7成员=" + household.members().size()
                + "/" + HouseholdRecord.MAX_MEMBERS + "，共享储物="
                + (household.sharedStorage() == null ? "未设置" : household.sharedStorage().toShortString())), false);
        HouseholdCareRecord care = data.care(household.id());
        if (care != null) {
            source.sendSuccess(() -> Component.literal("§6供养状态=" + care.summary()
                    + "；饥饿成员=" + care.hungryMembers() + "，严重饥饿="
                    + care.criticalMembers() + "，连续缺粮=" + care.shortageDays()
                    + "日，主动贡献=" + care.totalContributions() + "次"), false);
        }
        if (household.sharedStorage() != null
                && household.dimension().equals(player.serverLevel().dimension().location().toString())
                && player.serverLevel().hasChunkAt(household.sharedStorage())) {
            Container pantry = WarehouseContainers.asContainer(player.serverLevel()
                    .getBlockEntity(household.sharedStorage()));
            if (pantry != null) {
                int food = NpcFoodItems.edibleItems(pantry);
                source.sendSuccess(() -> Component.literal("§6家庭可直接食用的存粮=" + food + " 件"), false);
            }
        }
        for (HouseholdMemberRecord member : household.members()) {
            CharacterRecord record = characters.byId(member.characterId());
            String memberName = record == null ? member.characterId().toString().substring(0, 8) : record.name();
            source.sendSuccess(() -> Component.literal(" §8- §f" + memberName + " §7["
                    + member.role().displayName() + "]"), false);
        }
        if (household.sharedStorage() != null
                && household.dimension().equals(player.serverLevel().dimension().location().toString())) {
            mark(player, household.sharedStorage());
        }
        return 1;
    }

    private static int disband(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CharacterRecord character = CharacterRegistry.ensureCurrent(player);
        HouseholdRecord household = manageableHousehold(source, character.id());
        if (household == null) return 0;
        if (!HouseholdData.get(source.getServer()).disband(household.id())) return 0;
        WorldHistoryData.get(source.getServer()).append(player.serverLevel().getGameTime(),
                "household_disbanded", household.id(), household.name(), character.id().toString(), 10);
        source.sendSuccess(() -> Component.literal("§e家庭“" + household.name()
                + "”已解散；人物关系和各自私人财产仍然保留。"), false);
        return 1;
    }

    @Nullable
    private static HouseholdRecord manageableHousehold(CommandSourceStack source, UUID characterId) {
        HouseholdRecord household = HouseholdData.get(source.getServer()).forMember(characterId);
        if (household == null) {
            source.sendFailure(Component.literal("你尚未登记家庭。"));
            return null;
        }
        if (!source.hasPermission(2) && !household.headCharacterId().equals(characterId)
                && !controlsNpcHead(source, household.headCharacterId(), characterId)) {
            source.sendFailure(Component.literal("只有户主或管理员可以修改家庭登记。"));
            return null;
        }
        return household;
    }

    private static boolean controlsNpcHead(CommandSourceStack source, UUID headId, UUID characterId) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return false;
        for (var level : source.getServer().getAllLevels()) {
            if (level.getEntity(headId) instanceof NpcBase npc
                    && npc.isOwnedBy(player.getUUID())
                    && characterId.equals(npc.getOwnerCharacterId())) return true;
        }
        return false;
    }

    private static List<NpcBase> selectedOwned(ServerPlayer player) {
        List<NpcBase> result = new ArrayList<>();
        for (UUID id : CommandGroupAttachments.get(player).selectedMembers()) {
            Entity entity = player.serverLevel().getEntity(id);
            if (entity instanceof NpcBase npc && npc.isAlive() && npc.isOwnedBy(player.getUUID())) result.add(npc);
        }
        return result;
    }

    @Nullable
    private static NpcBase lookedAtNpc(ServerPlayer player) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1.0F).scale(REACH));
        AABB box = player.getBoundingBox().expandTowards(end.subtract(start)).inflate(1.0D);
        EntityHitResult hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                player, start, end, box, entity -> entity instanceof NpcBase && entity.isAlive(), REACH * REACH);
        return hit != null && hit.getEntity() instanceof NpcBase npc ? npc : null;
    }

    @Nullable
    private static BlockPos lookedBlock(ServerPlayer player) {
        HitResult hit = player.pick(REACH, 1.0F, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                ? block.getBlockPos().immutable() : null;
    }

    private static int noSelection(CommandSourceStack source) {
        source.sendFailure(Component.literal("请先用指挥杖选中至少一名属于你的、已加载的 NPC。"));
        return 0;
    }

    private static NpcRelationKind relationKind(HouseholdRole role) {
        return switch (role) {
            case SPOUSE, CHILD, KIN -> NpcRelationKind.FAMILY;
            case DEPENDENT, SERVANT -> NpcRelationKind.DEPENDENT;
            case LODGER, HEAD -> NpcRelationKind.FRIEND;
        };
    }

    private static int relationStrength(HouseholdRole role) {
        return switch (role) {
            case SPOUSE -> 72;
            case CHILD -> 78;
            case KIN -> 55;
            case DEPENDENT -> 42;
            case SERVANT -> 24;
            case LODGER -> 12;
            case HEAD -> 35;
        };
    }

    private static void mark(ServerPlayer player, BlockPos pos) {
        player.serverLevel().sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true,
                pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                24, 0.5D, 0.6D, 0.5D, 0.02D);
    }
}
