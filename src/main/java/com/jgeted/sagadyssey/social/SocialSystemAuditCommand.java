package com.jgeted.sagadyssey.social;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.social.character.CharacterRecord;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.LogisticsOrderStatus;
import com.jgeted.sagadyssey.social.life.NpcEmploymentContract;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.life.NpcNeedsRecord;
import com.jgeted.sagadyssey.social.life.NpcScheduleRecord;
import com.jgeted.sagadyssey.social.life.NpcWorkAuthority;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.household.HouseholdRole;
import com.jgeted.sagadyssey.social.household.HouseholdCareRecord;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.world.WorldDynamicsData;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 只读运行时审计；用于多人服升级存档后快速发现跨系统悬空引用。 */
public final class SocialSystemAuditCommand {
    private SocialSystemAuditCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga").then(Commands.literal("audit")
                .requires(source -> MinecraftApi.hasPermission(source, 2))
                .executes(ctx -> audit(ctx.getSource()))));
    }

    private static int audit(CommandSourceStack source) {
        var server = source.getServer();
        SettlementRegistryData settlements = SettlementRegistryData.get(server);
        CharacterRegistryData characters = CharacterRegistryData.get(server);
        PoliticalStateData politics = PoliticalStateData.get(server);
        LogisticsData logistics = LogisticsData.get(server);
        NpcLifeData npcLives = NpcLifeData.get(server);
        HouseholdData households = HouseholdData.get(server);
        List<String> issues = new ArrayList<>();

        Set<UUID> settlementIds = new HashSet<>();
        settlements.allSettlements().forEach(settlement -> {
            settlementIds.add(settlement.id());
            if (Identifier.tryParse(settlement.dimension()) == null)
                issues.add("聚落维度无效: " + settlement.name());
            UUID holder = politics.titleHolder(settlement.id());
            if (holder != null && !isLiving(characters, holder))
                issues.add("聚落头衔持有人失效: " + settlement.name());
        });

        for (String faction : politics.knownFactions()) {
            UUID ruler = politics.ruler(faction);
            if (ruler != null && (!isLiving(characters, ruler) || politics.isOfficeVacant(faction)))
                issues.add("统治者记录失效或空缺标记冲突: " + faction);
        }

        for (CharacterRecord character : characters.allCharacters()) {
            UUID heir = politics.designatedHeir(character.id());
            if (heir != null && !isLiving(characters, heir))
                issues.add("指定继承人失效: " + character.name());
            auditLiegeChain(character, politics, characters, issues);
        }

        Set<String> warehouseLocations = new HashSet<>();
        logistics.warehouses().forEach(warehouse -> {
            if (!settlementIds.contains(warehouse.settlementId()))
                issues.add("仓库引用不存在的聚落: " + warehouse.name());
            String location = warehouse.dimension() + "|" + warehouse.position().asLong();
            if (!warehouseLocations.add(location)) issues.add("同一容器被重复登记: " + warehouse.name());
        });
        logistics.orders().forEach(order -> {
            if (logistics.warehouse(order.sourceWarehouseId()) == null
                    || logistics.warehouse(order.destinationWarehouseId()) == null)
                issues.add("订单引用不存在的仓库: " + shortId(order.id()));
            if (order.requestedCount() < 1 || order.requestedCount() > LogisticsData.MAX_ORDER_ITEMS
                    || order.deliveredCount() < 0 || order.pickedUpCount() < order.deliveredCount()
                    || order.pickedUpCount() > order.requestedCount())
                issues.add("订单数量不变量损坏: " + shortId(order.id()));
            if (order.status() == LogisticsOrderStatus.IN_PROGRESS && order.assignedNpcId() == null)
                issues.add("运输中订单没有运输者: " + shortId(order.id()));
        });
        WorldDynamicsData.get(server).activeEvents().forEach(event -> {
            if (event.settlementId() != null && !settlementIds.contains(event.settlementId()))
                issues.add("世界事件引用不存在的聚落: " + event.type().displayName());
        });
        for (NpcEmploymentContract contract : npcLives.contracts()) {
            NpcLifeRecord life = npcLives.record(contract.npcId());
            if (!settlementIds.contains(contract.settlementId()))
                issues.add("NPC 合同引用不存在的聚落: " + shortId(contract.npcId()));
            if (contract.status().ongoing() && (life == null
                    || life.authority() != NpcWorkAuthority.CONTRACT
                    || !contract.employerCharacterId().equals(life.authorityId())
                    || !contract.settlementId().equals(life.activeWorkplaceSettlementId())))
                issues.add("履行中的 NPC 合同与工作权威不一致: " + shortId(contract.npcId()));
            if (contract.status().ongoing() && !isLiving(characters, contract.employerCharacterId()))
                issues.add("履行中的 NPC 合同雇主已经死亡: " + shortId(contract.npcId()));
        }
        for (NpcLifeRecord life : npcLives.records()) {
            NpcNeedsRecord needs = npcLives.needs(life.npcId());
            if (needs != null && (needs.hunger() < 0
                    || needs.hunger() > NpcNeedsRecord.MAX_HUNGER))
                issues.add("NPC 饥饿值越界: " + shortId(life.npcId()));
        }
        for (NpcScheduleRecord schedule : npcLives.schedules()) {
            if (npcLives.record(schedule.npcId()) == null)
                issues.add("NPC 日程没有对应生活档案: " + shortId(schedule.npcId()));
            if (schedule.reviewAt() < schedule.updatedAt())
                issues.add("NPC 日程复核时间早于状态开始时间: " + shortId(schedule.npcId()));
        }
        Set<UUID> householdMembers = new HashSet<>();
        households.households().forEach(household -> {
            if (household.settlementId() != null && !settlementIds.contains(household.settlementId()))
                issues.add("家庭引用不存在的聚落: " + household.name());
            if (!household.contains(household.headCharacterId())
                    || household.roleOf(household.headCharacterId()) != HouseholdRole.HEAD)
                issues.add("家庭户主不在成员表或身份错误: " + household.name());
            household.members().forEach(member -> {
                if (!householdMembers.add(member.characterId()))
                    issues.add("人物同时登记在多个家庭: " + shortId(member.characterId()));
                if (characters.byId(member.characterId()) == null)
                    issues.add("家庭成员没有社会人物档案: " + shortId(member.characterId()));
            });
            CharacterRecord head = characters.byId(household.headCharacterId());
            boolean hasLivingSuccessor = household.members().stream()
                    .filter(member -> !member.characterId().equals(household.headCharacterId()))
                    .anyMatch(member -> isLiving(characters, member.characterId()));
            if (head != null && !head.alive() && hasLivingSuccessor)
                issues.add("家庭有幸存成员但户主继承未处理: " + household.name());
            if (household.sharedStorage() != null) {
                if (logistics.warehouseAt(household.dimension(), household.sharedStorage()) != null)
                    issues.add("家庭共享储物同时被登记为公共仓库: " + household.name());
                if (npcLives.privateStorageAssigned(household.sharedStorage(), household.dimension()))
                    issues.add("家庭共享储物同时属于个人: " + household.name());
            }
            HouseholdCareRecord care = households.care(household.id());
            if (care == null) {
                issues.add("家庭缺少供养状态记录: " + household.name());
            } else {
                if (care.criticalMembers() > care.hungryMembers())
                    issues.add("家庭严重饥饿人数大于饥饿人数: " + household.name());
            }
        });

        if (issues.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§a社会系统审计通过：角色、政治、聚落、物流和事件引用一致。"), false);
            return 1;
        }
        source.sendFailure(Component.literal("发现 " + issues.size() + " 个一致性问题（最多显示 20 个）："));
        issues.stream().limit(20).forEach(issue ->
                source.sendFailure(Component.literal(" - " + issue)));
        return 0;
    }

    private static void auditLiegeChain(CharacterRecord character, PoliticalStateData politics,
                                        CharacterRegistryData characters, List<String> issues) {
        Set<UUID> seen = new HashSet<>();
        UUID cursor = character.id();
        for (int depth = 0; depth < 32; depth++) {
            cursor = politics.liegeOf(cursor);
            if (cursor == null) return;
            if (!seen.add(cursor)) {
                issues.add("封臣关系出现循环: " + character.name());
                return;
            }
            if (!isLiving(characters, cursor)) {
                issues.add("封臣关系指向失效人物: " + character.name());
                return;
            }
        }
        issues.add("封臣链过深: " + character.name());
    }

    private static boolean isLiving(CharacterRegistryData data, UUID id) {
        CharacterRecord record = data.byId(id);
        return record != null && record.alive();
    }

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }
}
