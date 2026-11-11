package com.jgeted.sagadyssey.knowledge.projection;

import com.jgeted.sagadyssey.knowledge.BeliefRecord;
import com.jgeted.sagadyssey.knowledge.ConflictState;
import com.jgeted.sagadyssey.knowledge.DisclosurePolicy;
import com.jgeted.sagadyssey.knowledge.FactKey;
import com.jgeted.sagadyssey.knowledge.FreshnessBand;
import com.jgeted.sagadyssey.knowledge.KnowledgeStateData;
import com.jgeted.sagadyssey.knowledge.PrecisionBand;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.faction.FactionAttachments;
import com.jgeted.sagadyssey.npc.identity.NpcIdentity;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.trade.NpcTradeAccessPolicy;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.household.HouseholdRecord;
import com.jgeted.sagadyssey.social.life.NpcEmploymentContract;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.service.AuthorityScope;
import com.jgeted.sagadyssey.social.service.ServiceRelationship;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.combat.CombatEncounterData;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.credit.CreditLedgerData;
import com.jgeted.sagadyssey.social.credit.CreditPurpose;
import com.jgeted.sagadyssey.social.governance.GovernanceLedgerData;
import com.jgeted.sagadyssey.social.governance.LevyStatus;
import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.security.SecurityData;
import com.jgeted.sagadyssey.social.scenario.ScenarioLabController;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds a fresh, viewer-scoped projection without serializing the NPC truth model. */
public final class NpcProjectionService {
    private NpcProjectionService() {}

    public static NpcViewSnapshot forViewer(ServerPlayer viewer, NpcBase npc) {
        return forViewer(viewer, npc, "");
    }

    public static NpcViewSnapshot forViewer(ServerPlayer viewer, NpcBase npc,
                                            String conversation) {
        UUID viewerId = CharacterRegistry.currentId(viewer);
        if (!viewer.isAlive() || viewerId == null) {
            // An unavailable observer gets no private facts, actions or new knowledge.
            return new NpcViewSnapshot(npc.getId(), npc.getCharacterId(), 0L,
                    new NpcViewSnapshot.DisplayIdentity("身份不可用", "", "", "", ""),
                    "当前没有可用的在生人物；查看不会创建人物。", "",
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    viewer.level().getGameTime() + 20L);
        }
        UUID npcId = npc.getCharacterId();
        long now = viewer.level().getGameTime();
        KnowledgeStateData knowledge = KnowledgeStateData.get(viewer.level().getServer());

        String health = observedHealth(npc.getHealth(), npc.getMaxHealth());
        String demeanor = observedDemeanor(npc.getMoral());
        knowledge.observe(viewerId, new FactKey(npcId, "identity.name", ""), npc.getNpcName(),
                PrecisionBand.EXACT, 1.0D, "当面观察", now, 168_000L, DisclosurePolicy.PUBLIC);
        knowledge.observe(viewerId, new FactKey(npcId, "identity.profession", ""),
                npc.getProfession().getDisplayName(), PrecisionBand.CATEGORY, 0.85D,
                "衣着与行为", now, 24_000L, DisclosurePolicy.SHAREABLE);
        knowledge.observe(viewerId, new FactKey(npcId, "condition.health", ""), health,
                PrecisionBand.IMPRESSION, 0.75D, "外观观察", now, 600L,
                DisclosurePolicy.SHAREABLE);
        knowledge.observe(viewerId, new FactKey(npcId, "condition.demeanor", ""), demeanor,
                PrecisionBand.IMPRESSION, 0.65D, "神态观察", now, 400L,
                DisclosurePolicy.SHAREABLE);

        ServiceRelationshipData services = ServiceRelationshipData.get(viewer.level().getServer());
        List<ServiceRelationship> activeForNpc = services.activeForAgent(npcId, now);
        List<ServiceRelationship> viewerServices = activeForNpc.stream()
                .filter(value -> value.principalId().equals(viewerId)).toList();
        Set<AuthorityScope> scopes = new HashSet<>();
        viewerServices.forEach(value -> scopes.addAll(value.scopes()));

        HouseholdData households = HouseholdData.get(viewer.level().getServer());
        HouseholdRecord targetHousehold = households.forMember(npcId);
        HouseholdRecord viewerHousehold = households.forMember(viewerId);
        boolean sameHousehold = targetHousehold != null && viewerHousehold != null
                && targetHousehold.id().equals(viewerHousehold.id());
        NpcLifeData lifeData = NpcLifeData.get(viewer.level().getServer());
        NpcLifeRecord lifeRecord = lifeData.record(npcId);
        SettlementRecord workplace = lifeRecord == null || lifeRecord.activeWorkplaceSettlementId() == null
                ? null : SettlementRegistryData.get(viewer.level().getServer())
                .byId(lifeRecord.activeWorkplaceSettlementId());
        boolean warehouseSteward = workplace != null && (viewerId.equals(workplace.founderCharacterId())
                || PoliticalStateData.get(viewer.level().getServer()).canGrantTitle(
                workplace.factionId(), workplace.id(), viewerId));
        ObserverClass observerClass = observerClass(!viewerServices.isEmpty(), sameHousehold,
                warehouseSteward, false);
        if (sameHousehold) {
            knowledge.document(viewerId, targetHousehold.id(),
                    new FactKey(npcId, "household.name", ""), targetHousehold.name(),
                    PrecisionBand.EXACT, 1.0D, now, 24_000L, DisclosurePolicy.PRIVATE);
        }
        if (warehouseSteward) {
            knowledge.document(viewerId, workplace.id(),
                    new FactKey(npcId, "workplace.name", ""), workplace.name(),
                    PrecisionBand.EXACT, 1.0D, now, 6_000L, DisclosurePolicy.PRIVATE);
        }

        String faction = observerClass == ObserverClass.STRANGER ? "所属尚不明确"
                : npc.getFaction() == null ? "无公开归属"
                : Component.translatable(npc.getFaction().displayName()).getString();
        String role = NpcIdentity.title(npc.getSocialClass(), npc.getOffice(),
                npc.getFaction() == null ? "sagadyssey:wilderness" : npc.getFaction().id(),
                npc.getGender()).getString();
        String appearance = npc.getGender().displayName().getString() + " · "
                + npc.getFaceVariant().displayName().getString();
        NpcViewSnapshot.DisplayIdentity identity = new NpcViewSnapshot.DisplayIdentity(
                npc.getNpcName(), role, npc.getProfession().getDisplayName(), faction, appearance);

        List<NpcViewSnapshot.ObservedCondition> conditions = new ArrayList<>();
        conditions.add(new NpcViewSnapshot.ObservedCondition("身体", health,
                health.equals("伤势明显") ? 2 : health.equals("似乎负伤") ? 1 : 0));
        conditions.add(new NpcViewSnapshot.ObservedCondition("神态", demeanor, 0));
        if (!npc.getWorkActivity().isBlank()) {
            conditions.add(new NpcViewSnapshot.ObservedCondition("正在做", npc.getWorkActivity(), 0));
        }

        List<NpcViewSnapshot.KnownFact> facts = projectBeliefs(
                knowledge.beliefsAbout(viewerId, npcId), now);
        List<NpcViewSnapshot.SystemInsight> insights = systemInsights(viewer, npc,
                viewerId, observerClass, sameHousehold, viewerServices, targetHousehold,
                lifeData, now);
        boolean hasHeldItem = !viewer.getMainHandItem().isEmpty() || !viewer.getOffhandItem().isEmpty();
        boolean fixedLabNpc = ScenarioLabController.isLabNpc(viewer.level().getServer(), npc.getUUID());
        NpcTradeAccessPolicy.Decision tradeAccess = NpcTradeAccessPolicy.evaluate(
                FactionAttachments.getStandings(viewer), npc.getFaction(), !viewerServices.isEmpty());
        List<NpcViewSnapshot.AvailableInteraction> actions = actions(npc, activeForNpc,
                viewerServices, scopes, hasHeldItem, fixedLabNpc, tradeAccess);
        List<NpcViewSnapshot.VisibleAsset> assets = visibleAssets(npc);
        List<NpcViewSnapshot.KnownAgreement> agreements = agreements(
                lifeData, viewerServices, npcId);

        String relationship = switch (observerClass) {
            case SERVICE_PARTY -> "你们有生效中的服务约定";
            case HOUSEHOLD -> "你们登记在同一家庭中";
            case WAREHOUSE_STEWARD -> "你代表其工作机构了解履职情况";
            case ADMIN, STRANGER -> activeForNpc.isEmpty()
                    ? "尚未建立正式关系" : "正在履行你未参与的约定";
        };
        long revision = 31L * npcId.hashCode() + 17L * viewerId.hashCode()
                + now / 20L + facts.hashCode() + actions.hashCode();
        return new NpcViewSnapshot(npc.getId(), npcId, revision, identity, relationship,
                conversation,
                conditions, facts, insights, actions, assets, agreements, now + 200L);
    }

    private static List<NpcViewSnapshot.SystemInsight> systemInsights(ServerPlayer viewer,
            NpcBase npc, UUID viewerId, ObserverClass observerClass, boolean sameHousehold,
            List<ServiceRelationship> viewerServices, HouseholdRecord targetHousehold,
            NpcLifeData lifeData, long now) {
        List<NpcViewSnapshot.SystemInsight> result = new ArrayList<>();
        UUID npcId = npc.getCharacterId();
        SubjectRef npcSubject = SubjectRef.character(npcId);
        SubjectRef viewerSubject = SubjectRef.character(viewerId);
        boolean privileged = sameHousehold || !viewerServices.isEmpty()
                || observerClass == ObserverClass.WAREHOUSE_STEWARD
                || observerClass == ObserverClass.ADMIN;

        InstitutionData institutions = InstitutionData.get(viewer.level().getServer());
        var publicOffice = institutions.officesHeldBy(npcSubject, now).stream().findFirst().orElse(null);
        if (publicOffice != null) {
            var institution = institutions.institution(publicOffice.institutionId());
            result.add(insight("社会", "公开职位",
                    publicOffice.title() + (institution == null ? "" : " · " + institution.name()),
                    "职位权限来自组织任命，并会随任期或撤销而失效。", 0,
                    PrecisionBand.EXACT, "公开身份"));
        } else if (sameHousehold && targetHousehold != null) {
            result.add(insight("社会", "家庭关系", targetHousehold.name(),
                    "你们登记在同一家庭；共享照料不等同于拥有对方全部财产。", 0,
                    PrecisionBand.EXACT, "家庭名册"));
        } else if (privileged && !institutions.membershipsOf(npcSubject).isEmpty()) {
            var membership = institutions.membershipsOf(npcSubject).getFirst();
            var institution = institutions.institution(membership.institutionId());
            result.add(insight("社会", "组织成员",
                    institution == null ? "已登记成员" : institution.name(),
                    "成员身份只在该组织内生效。", 0,
                    PrecisionBand.CATEGORY, "关系内信息"));
        } else {
            result.add(insight("社会", "你们的交集", "尚无可确认的共同组织",
                    "交谈、共同工作或可信文件可能增加你对其社会关系的了解。", 0,
                    PrecisionBand.IMPRESSION, "当前认知"));
        }

        NpcEmploymentContract contract = lifeData.contract(npcId);
        if (contract != null && !viewerServices.isEmpty()) {
            result.add(insight("经济", "服务与工资",
                    "每期 " + contract.wageEmeralds() + " 绿宝石 · 欠付 "
                            + contract.unpaidEmeralds(),
                    "工资来自实体金库；欠付会保留为可追溯义务。", contract.unpaidEmeralds() > 0 ? 2 : 0,
                    PrecisionBand.EXACT, "你参与的服务约定"));
        } else {
            String debt = directDebtSummary(viewer, npcSubject, viewerSubject, now);
            if (debt != null) {
                result.add(insight("经济", "双方信用", debt,
                        "信用只针对你们双方与具体用途，不是全世界共享的分数。", 1,
                        PrecisionBand.EXACT, "当事人信用账本"));
            } else if (privileged) {
                var obligations = GovernanceLedgerData.get(viewer.level().getServer())
                        .obligationsOf(npcSubject, now).stream()
                        .filter(value -> value.status() == LevyStatus.ASSESSED
                                || value.status() == LevyStatus.PARTIALLY_PAID
                                || value.status() == LevyStatus.OVERDUE).toList();
                result.add(insight("经济", obligations.isEmpty() ? "当前义务" : "待履行义务",
                        obligations.isEmpty() ? "未见与你相关的未结项目"
                                : obligations.size() + " 项仍未结清",
                        "税、租、会费和罚金只有实物进入对应金库后才算履行。",
                        obligations.stream().anyMatch(value -> value.status() == LevyStatus.OVERDUE) ? 2 : 0,
                        PrecisionBand.CATEGORY, "关系内账本"));
            } else {
                result.add(insight("经济", "他愿意谈什么", "仅限双方真实持有且愿意处分的物品",
                        "价格来自本地交易经验、库存压力、路程风险和你们的关系。", 0,
                        PrecisionBand.CATEGORY, "当面议价规则"));
            }
        }

        var triage = InjuryData.get(viewer.level().getServer()).triage(npcId, now);
        var encounter = CombatEncounterData.get(viewer.level().getServer()).activeFor(npcId, now);
        var relatedIncidents = SecurityData.get(viewer.level().getServer()).incidentsAbout(npcSubject).stream()
                .filter(value -> value.reportedBy().equals(viewerSubject)
                        || viewerSubject.equals(value.victim())
                        || viewerSubject.equals(value.allegedOffender())).toList();
        if (!relatedIncidents.isEmpty()) {
            var incident = relatedIncidents.getFirst();
            result.add(insight("战斗", "共同治安记录", securityStatus(incident.status()),
                    "你是该报告的当事人，因此可以看到其核验与响应状态。",
                    incident.status() == com.jgeted.sagadyssey.social.security.IncidentStatus.RESPONDING ? 2 : 1,
                    PrecisionBand.EXACT, "当事人治安记录"));
        } else if (encounter != null) {
            var side = encounter.sideOf(npcId);
            String summary = privileged && side != null
                    ? "交战规则：" + engagementRule(side.rule()) : "正处于一次可见冲突中";
            result.add(insight("战斗", "当前冲突", summary,
                    privileged ? "你们的关系允许你知道其当前战斗规则。"
                            : "具体命令与敌我名单并未向你公开。", 2,
                    privileged ? PrecisionBand.EXACT : PrecisionBand.IMPRESSION,
                    privileged ? "关系内战斗信息" : "当面观察"));
        } else {
            String summary = triage.activeWounds() == 0 ? "未见明显持续伤病"
                    : triage.bleeding() >= 40 ? "可见出血，需要尽快处理"
                    : triage.mobilityPenalty() >= 40 ? "动作受限，可能有肢体伤"
                    : "可观察到尚未痊愈的伤势";
            int urgency = triage.mortalDanger() ? 3 : triage.activeWounds() > 0 ? 1 : 0;
            result.add(insight("战斗", "身体状态", summary,
                    "这里只显示外观能够支持的判断；精确创伤值仍保留在后端病历。",
                    urgency, PrecisionBand.IMPRESSION, "当面观察"));
        }
        return result.stream().limit(3).toList();
    }

    private static String directDebtSummary(ServerPlayer viewer, SubjectRef npc,
                                            SubjectRef viewerSubject, long now) {
        CreditLedgerData credits = CreditLedgerData.get(viewer.level().getServer());
        int viewerOwes = 0;
        int npcOwes = 0;
        for (CreditPurpose purpose : CreditPurpose.values()) {
            viewerOwes += credits.profile(npc, viewerSubject, purpose, now).currentOutstanding();
            npcOwes += credits.profile(viewerSubject, npc, purpose, now).currentOutstanding();
        }
        if (viewerOwes > 0) return "你尚欠对方 " + viewerOwes + " 枚绿宝石";
        if (npcOwes > 0) return "对方尚欠你 " + npcOwes + " 枚绿宝石";
        return null;
    }

    private static NpcViewSnapshot.SystemInsight insight(String category, String title,
            String summary, String detail, int urgency, PrecisionBand precision, String source) {
        return new NpcViewSnapshot.SystemInsight(category, title, summary, detail, urgency,
                precision, FreshnessBand.CURRENT, source);
    }

    private static String engagementRule(com.jgeted.sagadyssey.social.combat.EngagementRule rule) {
        return switch (rule) {
            case SELF_DEFENSE_ONLY -> "仅自卫";
            case PROTECT_PERSONS -> "保护人员";
            case PROTECT_LOCATION -> "守护地点";
            case ARREST_IF_POSSIBLE -> "优先逮捕";
            case LETHAL_FORCE -> "允许致命武力";
        };
    }

    private static String securityStatus(com.jgeted.sagadyssey.social.security.IncidentStatus status) {
        return switch (status) {
            case REPORTED -> "仅已报告，尚未核验";
            case UNDER_REVIEW -> "正在核对证词与实物证据";
            case VERIFIED -> "已经核验";
            case RESPONDING -> "巡逻人员正在响应";
            case RESOLVED -> "已经结案";
            case DISMISSED -> "证据不足，已撤销";
        };
    }

    static List<NpcViewSnapshot.KnownFact> projectBeliefs(List<BeliefRecord> beliefs, long now) {
        Map<String, List<BeliefRecord>> grouped = new LinkedHashMap<>();
        for (BeliefRecord belief : beliefs) {
            String key = belief.key().predicate() + '\u0000' + belief.key().qualifier();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(belief);
        }
        List<NpcViewSnapshot.KnownFact> result = new ArrayList<>();
        for (List<BeliefRecord> group : grouped.values()) {
            group.sort(Comparator.comparingLong(BeliefRecord::lastConfirmedAt).reversed());
            BeliefRecord chosen = group.getFirst();
            boolean conflict = group.stream().map(BeliefRecord::displayValue).distinct().count() > 1;
            FreshnessBand freshness = chosen.staleAt(now) ? FreshnessBand.STALE
                    : now - chosen.lastConfirmedAt() > 6_000L ? FreshnessBand.RECENT
                    : FreshnessBand.CURRENT;
            result.add(new NpcViewSnapshot.KnownFact(label(chosen.key().predicate()),
                    chosen.displayValue(), chosen.precision(), chosen.source(), chosen.sourceLabel(),
                    freshness, conflict ? ConflictState.CONTESTED : ConflictState.NONE,
                    chosen.precision() == PrecisionBand.EXACT));
        }
        return result.stream().limit(8).toList();
    }

    private static List<NpcViewSnapshot.AvailableInteraction> actions(NpcBase npc,
            List<ServiceRelationship> activeForNpc, List<ServiceRelationship> viewerServices,
            Set<AuthorityScope> scopes, boolean hasHeldItem, boolean fixedLabNpc,
            NpcTradeAccessPolicy.Decision tradeAccess) {
        List<NpcViewSnapshot.AvailableInteraction> result = new ArrayList<>();
        result.add(new NpcViewSnapshot.AvailableInteraction("talk", "交谈", "交流",
                "继续当面交流；具体话题只会来自对方知道且愿意透露的内容"));
        if (hasHeldItem) result.add(new NpcViewSnapshot.AvailableInteraction("handover",
                "递交 / 展示手持物", "物品", "选择赠礼、付款、交付、配发、借用或展示"));
        if (!npc.getEquipmentInventory().isEmpty() && tradeAccess.allowed()) {
            result.add(new NpcViewSnapshot.AvailableInteraction("trade", "提出交易", "谈判",
                    "查看对方此刻愿意公开并处分的真实物品；" + tradeAccess.explanation()));
        }
        if (!fixedLabNpc && activeForNpc.isEmpty() && npc.isSociallyRecruitable()) {
            int cost = npc.getAdjustedRecruitmentCost();
            if (npc.getFaction() != null && npc.getFaction().canBeHostile()) cost *= 2;
            result.add(new NpcViewSnapshot.AvailableInteraction("recruit",
                    "提出服务约定", "关系", "建议签约礼：" + cost + " 枚绿宝石"));
        }
        addScope(result, scopes, AuthorityScope.FOLLOW, "follow", "请求随行", "指令");
        addScope(result, scopes, AuthorityScope.HOLD_POSITION, "stay", "请求驻守", "指令");
        if (supportsWork(npc.getProfession())) {
            addScope(result, scopes, AuthorityScope.WORK, "work", "开始约定工作", "工作");
        }
        addScope(result, scopes, AuthorityScope.OPERATE_MOUNT, "request_mounts", "管理工作坐骑", "资产");
        addScope(result, scopes, AuthorityScope.END_SERVICE, "dismiss", "解除服务约定", "关系");
        return result;
    }

    private static void addScope(List<NpcViewSnapshot.AvailableInteraction> actions,
                                 Set<AuthorityScope> scopes, AuthorityScope scope,
                                 String id, String label, String category) {
        if (scopes.contains(scope)) actions.add(new NpcViewSnapshot.AvailableInteraction(
                id, label, category, "依据：" + scope.displayName() + "授权"));
    }

    private static List<NpcViewSnapshot.VisibleAsset> visibleAssets(NpcBase npc) {
        List<NpcViewSnapshot.VisibleAsset> result = new ArrayList<>();
        addVisible(result, "主手", npc.getItemBySlot(EquipmentSlot.MAINHAND));
        addVisible(result, "副手", npc.getItemBySlot(EquipmentSlot.OFFHAND));
        addVisible(result, "头部", npc.getItemBySlot(EquipmentSlot.HEAD));
        addVisible(result, "躯干", npc.getItemBySlot(EquipmentSlot.CHEST));
        addVisible(result, "腿部", npc.getItemBySlot(EquipmentSlot.LEGS));
        addVisible(result, "足部", npc.getItemBySlot(EquipmentSlot.FEET));
        return result;
    }

    private static void addVisible(List<NpcViewSnapshot.VisibleAsset> result,
                                   String location, ItemStack stack) {
        if (stack.isEmpty()) return;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        result.add(new NpcViewSnapshot.VisibleAsset(location, id,
                stack.getHoverName().getString(), PrecisionBand.IMPRESSION));
    }

    private static List<NpcViewSnapshot.KnownAgreement> agreements(NpcLifeData life,
            List<ServiceRelationship> relationships, UUID npcId) {
        List<NpcViewSnapshot.KnownAgreement> result = new ArrayList<>();
        for (ServiceRelationship relationship : relationships) {
            String terms = relationship.scopes().stream().map(AuthorityScope::displayName)
                    .sorted().limit(5).reduce((a, b) -> a + "、" + b).orElse("无行动授权");
            result.add(new NpcViewSnapshot.KnownAgreement("服务约定",
                    relationship.status().displayName(), terms,
                    relationship.endsAt() == null ? -1L : relationship.endsAt()));
        }
        NpcEmploymentContract contract = life.contract(npcId);
        if (contract != null && relationships.stream().anyMatch(value ->
                value.principalId().equals(contract.employerCharacterId()))) {
            result.add(new NpcViewSnapshot.KnownAgreement("实物工资",
                    contract.status().displayName(), "每期 " + contract.wageEmeralds()
                    + " 枚绿宝石；欠付 " + contract.unpaidEmeralds(), contract.endsAt()));
        }
        return result;
    }

    private static boolean supportsWork(NpcProfession profession) {
        return switch (profession) {
            case WORKER, BLACKSMITH, FARMER, BARD, MEDIC, TRADER, COURIER -> true;
            default -> false;
        };
    }

    private static String observedHealth(float current, float maximum) {
        float ratio = maximum <= 0.0F ? 0.0F : current / maximum;
        if (ratio < 0.3F) return "伤势明显";
        if (ratio < 0.7F) return "似乎负伤";
        return "未见明显重伤";
    }

    private static String observedDemeanor(int hiddenMorale) {
        if (hiddenMorale < 25) return "显得紧张而退缩";
        if (hiddenMorale < 50) return "神情谨慎";
        if (hiddenMorale > 80) return "显得坚定";
        return "情绪平稳";
    }

    private static String label(String predicate) {
        return switch (predicate) {
            case "identity.name" -> "姓名";
            case "identity.profession" -> "职业印象";
            case "condition.health" -> "健康印象";
            case "condition.demeanor" -> "神态印象";
            case "household.name" -> "家庭";
            case "workplace.name" -> "工作机构";
            case "need.food" -> "食物需求";
            default -> predicate;
        };
    }

    static ObserverClass observerClass(boolean serviceParty, boolean household,
                                       boolean warehouseSteward, boolean administrator) {
        if (administrator) return ObserverClass.ADMIN;
        if (serviceParty) return ObserverClass.SERVICE_PARTY;
        if (household) return ObserverClass.HOUSEHOLD;
        if (warehouseSteward) return ObserverClass.WAREHOUSE_STEWARD;
        return ObserverClass.STRANGER;
    }

    enum ObserverClass { STRANGER, HOUSEHOLD, SERVICE_PARTY, WAREHOUSE_STEWARD, ADMIN }
}
