package com.jgeted.sagadyssey.social.character;

import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.faction.FactionAttachments;
import com.jgeted.sagadyssey.npc.identity.PlayerIdentityAttachments;
import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.service.ServiceStatus;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.UUID;

/**
 * 玩家账号与社会人物的生命周期桥梁。
 * 有能力支付赎命代价时属于游戏性死亡；支付不起时才结束当前人物的一生。
 */
public final class CharacterLifecycleEvents {
    private static final int BASE_RESCUE_COST = 4;

    private CharacterLifecycleEvents() {}

    /**
     * 所有 NPC 生成入口（指令、生成蛋、结构和旧存档加载）统一经过这里。
     * 角色档案与生活档案不再依赖固定场景命令顺手补写。
     */
    @SubscribeEvent
    public static void onNpcJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof NpcBase npc)) return;
        npc.setPersistenceRequired();
        npc.restoreServiceIndependentFaction();
        String factionId = npc.getFaction() == null
                ? "sagadyssey:wilderness" : npc.getFaction().id();
        UUID characterId = npc.getCharacterId();
        CharacterRegistryData characters = CharacterRegistryData.get(level.getServer());
        characters.ensureNpcCharacter(
                characterId, npc.getName().getString(), npc.getSocialClass(), npc.getOffice(),
                factionId, level.getGameTime());
        characters.bindNpcBody(characterId, level.dimension().location().toString(),
                npc.getUUID(), level.getGameTime());
        RightsStateData assetRights = RightsStateData.get(level.getServer());
        SubjectRef npcSubject = SubjectRef.character(characterId);
        assetRights.ensureContext(ContainerIds.npcPersonal(characterId),
                "npc:" + characterId + ":personal", npcSubject, npcSubject, AccessPolicy.PRIVATE);
        assetRights.ensureContext(ContainerIds.npcCargo(characterId),
                "npc:" + characterId + ":cargo", npcSubject, npcSubject, AccessPolicy.CONTRACT);
        NpcLifeData.get(level.getServer()).ensureRegistered(npc, level.getGameTime());
        reconcileLegacyServiceProjection(level, npc, characters);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // A dead body can log back in before the player chooses Respawn.
        // Synchronize its existing attachments, but never create a life here.
        if (!player.isAlive()) {
            FactionAttachments.syncToClient(player);
            return;
        }
        CharacterRecord record = CharacterRegistry.ensureCurrent(player);
        reconcilePoliticalIdentity(player, record);
        syncCompatibilityIdentity(player, record);
        FactionAttachments.syncToClient(player);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof NpcBase npc) {
            handleRegisteredNpcDeath(event, npc);
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isCreative() || player.isSpectator()) return;

        CharacterRecord record = CharacterRegistry.current(player);
        if (record == null) return; // Repeated death notification is not a new life.
        int cost = rescueCost(record);
        if (countEmeralds(player) >= cost) {
            removeEmeralds(player, cost);
            CharacterRegistryData.get(player.server).markRescued(player.getUUID());
            WorldHistoryData.get(player.server).append(player.serverLevel().getGameTime(),
                    "character_rescued", record.id(), record.name(), event.getSource().getMsgId(),
                    Math.min(100, 12 + record.precedence() * 5));
            event.setCanceled(true);
            rescueAtSpawn(player);
            player.sendSystemMessage(Component.translatable(
                    "message.sagadyssey.character.rescued", record.name(), cost,
                    record.rescueCount()));
            return;
        }

        // 先让所有已加载的旧版 NPC 绑定到上一世，再解除账号的当前人物绑定。
        sealLoadedLegacyOwnership(player);
        CharacterRecord dead = CharacterRegistryData.get(player.server).markCurrentDead(
                player.getUUID(), player.serverLevel().getGameTime(), event.getSource().getMsgId());
        PlayerIdentityAttachments.get(player).resetForNewCharacter();
        FactionAttachments.getStandings(player).resetForNewCharacter();
        CommandGroupAttachments.get(player).resetForNewCharacter();
        if (dead != null) {
            HouseholdData.get(player.server).handleCharacterDeath(player.server, dead.id(),
                    player.serverLevel().getGameTime());
            NpcLifeData.get(player.server).onAuthorityDeath(player.server, dead.id(),
                    player.serverLevel().getGameTime());
            ServiceRelationshipData.get(player.server).endActiveForPrincipal(dead.id(),
                    ServiceStatus.CANCELLED, "委托人物永久死亡，服务关系不由下一世继承",
                    player.serverLevel().getGameTime());
            PoliticalStateData.get(player.server).handleCharacterDeath(player.server,
                    dead.id(), dead.name(), player.serverLevel().getGameTime());
            refreshOnlinePoliticalIdentities(player.server);
            WorldHistoryData.get(player.server).append(player.serverLevel().getGameTime(),
                    "character_died", dead.id(), dead.name(), dead.deathCause(),
                    dead.historicalImportance());
            player.sendSystemMessage(Component.translatable(
                    "message.sagadyssey.character.permanent_death", dead.name(), cost));
        }
    }

    /** 只有已进入角色注册表的 NPC 才参与永久死亡和政治继承。 */
    private static void handleRegisteredNpcDeath(LivingDeathEvent event, NpcBase npc) {
        if (!(npc.level() instanceof ServerLevel level)) return;
        NpcLifeData.get(level.getServer()).onNpcDeath(npc.getCharacterId(), level.getGameTime());
        ServiceRelationshipData.get(level.getServer()).endActiveForAgent(npc.getCharacterId(),
                ServiceStatus.CANCELLED, "代理人物死亡，服务关系终止", level.getGameTime());
        CharacterRegistryData characters = CharacterRegistryData.get(level.getServer());
        CharacterRecord dead = characters.markCharacterDead(npc.getCharacterId(), level.getGameTime(),
                event.getSource().getMsgId());
        if (dead == null) return;
        HouseholdData.get(level.getServer()).handleCharacterDeath(level.getServer(), dead.id(),
                level.getGameTime());
        PoliticalStateData.get(level.getServer()).handleCharacterDeath(level.getServer(),
                dead.id(), dead.name(), level.getGameTime());
        WorldHistoryData.get(level.getServer()).append(level.getGameTime(), "character_died",
                dead.id(), dead.name(), dead.deathCause(), dead.historicalImportance());
        refreshOnlinePoliticalIdentities(level.getServer());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.isAlive() || CharacterRegistry.current(player) != null) return;

        CharacterRecord record = CharacterRegistryData.get(player.server).createFreshLife(player);
        PlayerIdentityAttachments.get(player).resetForNewCharacter();
        syncCompatibilityIdentity(player, record);
        FactionAttachments.getStandings(player).resetForNewCharacter();
        CommandGroupAttachments.get(player).resetForNewCharacter();
        FactionAttachments.syncToClient(player);
        CommandGroupAttachments.sync(player, false);
        WorldHistoryData.get(player.server).append(player.serverLevel().getGameTime(),
                "character_appeared", record.id(), record.name(), "", 5);
        player.sendSystemMessage(Component.translatable(
                "message.sagadyssey.character.new_life", record.name(), record.id().toString()));
    }

    private static int rescueCost(CharacterRecord record) {
        return BASE_RESCUE_COST + record.precedence() * 4 + record.rescueCount() * 2;
    }

    private static int countEmeralds(Player player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.EMERALD)) count += stack.getCount();
        }
        return count;
    }

    private static void removeEmeralds(Player player, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(Items.EMERALD)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
        }
        player.getInventory().setChanged();
    }

    private static void rescueAtSpawn(ServerPlayer player) {
        ServerLevel level = player.server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        player.teleportTo(level, spawn.getX() + 0.5D, spawn.getY() + 1.0D, spawn.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.fallDistance = 0.0F;
        player.clearFire();
        player.setHealth(Math.max(1.0F, player.getMaxHealth() * 0.35F));
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 2));
    }

    private static void sealLoadedLegacyOwnership(ServerPlayer player) {
        for (ServerLevel level : player.server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof NpcBase npc
                        && player.getUUID().equals(npc.getOwnerUUID())
                        && npc.getOwnerCharacterId() == null) {
                    npc.bindLegacyOwnerToCurrent(player);
                    ServiceRelationshipData.get(player.server).ensureLegacyRecruitment(
                            npc.getOwnerCharacterId(), npc.getCharacterId(),
                            player.serverLevel().getGameTime());
                }
            }
        }
    }

    private static void reconcileLegacyServiceProjection(ServerLevel level, NpcBase npc,
                                                         CharacterRegistryData characters) {
        ServiceRelationshipData services = ServiceRelationshipData.get(level.getServer());
        UUID principal = npc.getOwnerCharacterId();
        if (principal == null && npc.getOwnerUUID() != null) {
            principal = characters.compatibilityOwnerCharacter(npc.getOwnerUUID());
        }
        if (!services.hasHistory(npc.getCharacterId()) && principal != null) {
            services.ensureLegacyRecruitment(principal, npc.getCharacterId(), level.getGameTime());
            CharacterRecord principalRecord = characters.byId(principal);
            if (principalRecord != null && !principalRecord.alive()) {
                services.endActive(principal, npc.getCharacterId(), ServiceStatus.CANCELLED,
                        "旧委托人物已经死亡，关系不能由下一世继承", level.getGameTime());
            }
        }
        var active = services.activeForAgent(npc.getCharacterId(), level.getGameTime());
        if (!active.isEmpty()) {
            UUID activePrincipal = active.getFirst().principalId();
            npc.syncLegacyServiceProjection(characters.accountForCharacter(activePrincipal), activePrincipal);
        } else if (services.hasHistory(npc.getCharacterId())) {
            npc.syncLegacyServiceProjection(null, null);
        }
        npc.restoreServiceIndependentFaction();
    }

    private static void syncCompatibilityIdentity(ServerPlayer player, CharacterRecord record) {
        PlayerIdentityAttachments.get(player)
                .set(record.socialClass(), record.office(), record.factionId());
    }

    /** 政治表是职务的权威来源；继承后即使继承人离线，再登录也会得到正确称谓和权限。 */
    private static void reconcilePoliticalIdentity(ServerPlayer player, CharacterRecord record) {
        PoliticalStateData politics = PoliticalStateData.get(player.server);
        NpcSocialClass socialClass = record.socialClass();
        NpcOffice office = record.office();
        String faction = record.factionId();

        String ruledFaction = politics.ruledFaction(record.id());
        if (ruledFaction != null) {
            socialClass = socialClass.precedence() < NpcSocialClass.ROYAL.precedence()
                    ? NpcSocialClass.ROYAL : socialClass;
            office = NpcOffice.SOVEREIGN;
            faction = ruledFaction;
        } else if (!politics.titlesOf(record.id()).isEmpty()) {
            socialClass = socialClass.precedence() < NpcSocialClass.NOBLE.precedence()
                    ? NpcSocialClass.NOBLE : socialClass;
            office = NpcOffice.LOCAL_RULER;
            var title = politics.titlesOf(record.id()).iterator().next();
            var settlement = SettlementRegistryData.get(player.server).byId(title);
            if (settlement != null) faction = settlement.factionId();
        } else if (politics.liegeOf(record.id()) != null) {
            office = NpcOffice.SWORN;
        } else if (office == NpcOffice.SOVEREIGN || office == NpcOffice.LOCAL_RULER
                || office == NpcOffice.SWORN) {
            office = NpcOffice.NONE;
        }

        if (socialClass != record.socialClass() || office != record.office()
                || !faction.equals(record.factionId())) {
            CharacterRegistryData.get(player.server).updateIdentity(player.getUUID(), socialClass, office, faction);
        }
    }

    private static void refreshOnlinePoliticalIdentities(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            CharacterRecord record = CharacterRegistry.current(online);
            if (record == null) continue;
            reconcilePoliticalIdentity(online, record);
            syncCompatibilityIdentity(online, record);
        }
    }
}
