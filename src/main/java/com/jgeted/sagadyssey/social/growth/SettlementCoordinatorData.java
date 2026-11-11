package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.structure.NaturalFarmRules;
import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkDepositTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkPlanFactory;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.needs.NeedRequestData;
import com.jgeted.sagadyssey.social.needs.NeedRequestStatus;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.social.production.ProductionService;
import com.jgeted.sagadyssey.social.production.ProductionMaterial;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.AssetRight;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsService;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.RightsVerdict;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 只保存决策、责任和原回执引用；人物、库存和物权仍归原领域账本。 */
public final class SettlementCoordinatorData extends NbtSavedData {
    public static final String NAME = "sagadyssey_settlement_coordinator_v1";
    private static final Factory<SettlementCoordinatorData> FACTORY = new Factory<>(
            SettlementCoordinatorData::new, SettlementCoordinatorData::load);
    private final Map<String, CompoundTag> sites = new LinkedHashMap<>();

    public static SettlementCoordinatorData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.type(NAME));
    }

    public static SettlementCoordinatorData existing(MinecraftServer server) {
        return server.overworld().getDataStorage().get(FACTORY.type(NAME));
    }

    public static boolean active(MinecraftServer server, String key) {
        var data = existing(server);
        return data != null && data.sites.containsKey(key);
    }

    public List<CompoundTag> sites() {
        return sites.values().stream().map(CompoundTag::copy).toList();
    }

    public CompoundTag site(String key) {
        var row = sites.get(key);
        return row == null ? null : row.copy();
    }

    static NaturalVillageBuilding building(CompoundTag row) {
        return NaturalVillageLifeData.decode(row.getCompoundOrEmpty("Village"));
    }

    CompoundTag resident(UUID person) {
        for (var row : sites.values()) {
            var village = building(row);
            for (int i = 0; i < 3; i++) {
                if (NaturalVillageLifeData.person(village, i).equals(person)) return row;
            }
        }
        return null;
    }

    /** 正式启用入口；检查现任治理权及旧在途工程，不在状态查询中自动迁移。 */
    public String activate(MinecraftServer server, String key, UUID official, long now) {
        if (sites.containsKey(key)) return "ALREADY_ACTIVE";
        var growth = SettlementGrowthData.existing(server);
        var previous = growth == null ? null : growth.site(key);
        if (previous == null) return "NO_REGISTERED_GROWTH_SITE";
        var village = building(previous);
        if (!NaturalPolityData.get(server).authorized(server, village, official)) return "UNAUTHORIZED";
        if (previous.getBooleanOr("Review", false) || previous.getBooleanOr("Submitting", false)
                || previous.getBooleanOr("MaterialCarried", false) || previous.getBooleanOr("Feeding", false)
                || previous.getIntOr("Placed", 0) != 0 || previous.getIntOr("Work", 0) != 0
                || !List.of("WAITING_DECISION", "MATERIALS").contains(previous.getStringOr("HouseStage", ""))) {
            return "OLD_CONSTRUCTION_OBLIGATION_REQUIRES_RECONCILIATION";
        }
        var row = new CompoundTag();
        row.put("Village", previous.getCompoundOrEmpty("Village").copy());
        row.putInt("PolicyVersion", SettlementPolicy.VERSION);
        row.putLong("Activated", now);
        row.putLong("NeedCreated", previous.contains("HouseApprovedAt") ? previous.getLongOr("HouseApprovedAt", 0L) : now);
        row.store("ApprovedBy", UUIDUtil.CODEC, official);
        row.putString("OldGrowthReference", key);
        row.put("OldGrowthSnapshot", previous);
        row.putBoolean("Harvest", true);
        row.put("Jobs", new CompoundTag());
        row.putString("Reason", "保留原居民、库存、运输及工程义务，开始分项协作");
        SettlementResourceSurvey.declareParcel(row, village, official, now);
        sites.put(key, row);
        for (String job : List.of("LOGS", "PLANKS", "WOOL", "BED", "TEMP_BED", "STONE", "DOOR", "HOUSE", "RELOCATE", "SURVEY")) {
            var task = new CompoundTag();
            task.put("Village", row.getCompoundOrEmpty("Village").copy());
            task.putString("Kind", job);
            task.putString("Id", key + ":coord-v1:" + job);
            task.putString("ProjectKey", task.getStringOr("Id", ""));
            task.putString("NeedId", key + ":housing");
            task.putString("State", "WAITING");
            task.putString("Reason", "等待前置条件");
            task.putLong("Created", row.getLongOr("NeedCreated", 0L));
            task.putLong("LastProgress", now);
            task.putString("HouseStage", job.equals("HOUSE") ? "BUILD" : job);
            row.getCompoundOrEmpty("Jobs").put(job, task);
        }
        job(row, "BED").putString("DependsOn", "PLANKS");
        job(row, "TEMP_BED").putString("DependsOn", "BED");
        job(row, "RELOCATE").putString("DependsOn", "TEMP_BED,HOUSE:56");
        setDirty();
        // A new formal v6 activation includes the approved local safety charter.
        // The ALREADY_ACTIVE branch above leaves existing worlds opt-in.
        SettlementSafetyData.get(server).activate(server, key, official, now);
        return "ACTIVATED";
    }

    static CompoundTag job(CompoundTag row, String kind) { return row.getCompoundOrEmpty("Jobs").getCompoundOrEmpty(kind); }
    static boolean done(CompoundTag row, String kind) { return job(row, kind).getStringOr("State", "").equals("DONE"); }
    static ItemFingerprint item(Item item) { return ItemFingerprint.of(new ItemStack(item)); }
    static Container store(MinecraftServer server, NaturalVillageBuilding village, int index) {
        return NaturalPolityData.inventory(server, village, index);
    }

    /** 库存检查沿原权利解析，剔除私人寄存及已预留份额。 */
    static int available(MinecraftServer server, NaturalVillageBuilding village, int index, Item type) {
        return availableProtected(server, village, index, type, 0);
    }

    static int availableProtected(MinecraftServer server, NaturalVillageBuilding village, int index, Item type, int protection) {
        var box = store(server, village, index);
        var id = NaturalVillageEconomyData.storeId(village, index);
        if (box == null) return 0;
        var owner = index == 3 ? SubjectRef.institution(village.settlementId())
                : SubjectRef.household(NaturalVillageLifeData.home(village));
        var rights = RightsStateData.get(server);
        var resolver = new RightsService(rights);
        int count = 0;
        long now = server.overworld().getGameTime();
        for (int slot = 0; slot < box.getContainerSize(); slot++) {
            var stack = box.getItem(slot);
            if (stack.is(type) && resolver.can(owner, AssetRight.TRANSFER, id, slot, item(type), now)
                    .status() == RightsVerdict.Status.ALLOW) count += stack.getCount();
        }
        // 这里没有同用途播种预留的证明，不能把运输承诺和留种保护误作重叠。
        return NaturalFarmRules.purposeAvailable(count, protection, 0,
                rights.reservedQuantity(id, item(type), null, now));
    }

    int stoneDeficit(MinecraftServer server, CompoundTag row) {
        if(SettlementDevelopmentLayout.supports(building(row))) { int extra=SettlementDevelopmentData.materialDeficit(server,building(row)); if(extra>0)return extra; }
        if (houseMaterial(row) != Items.COBBLESTONE) return 0;
        var village = building(row);
        int placed = Math.min(55, job(row, "HOUSE").getIntOr("Placed", 0));
        int carried = job(row, "HOUSE").getBooleanOr("MaterialCarried", false) && placed < 55 ? 1 : 0;
        int donation = job(row, "STONE").getBooleanOr("Cargo", false) ? job(row, "STONE").getIntOr("Quantity", 0) : 0;
        return SettlementPolicy.materialDeficit(55, placed, SettlementMaterials.available(server,village,1)
                + SettlementMaterials.available(server,village,3) + SettlementMaterials.available(server,village,2), carried + donation);
    }

    static Item houseMaterial(CompoundTag row) {
        return job(row, "HOUSE").getStringOr("WallMaterial", "").equals("WOOD") ? Items.OAK_PLANKS : Items.COBBLESTONE;
    }

    private void assess(MinecraftServer server, CompoundTag row, long now) {
        var village = building(row);
        var level = SupplyFlowService.level(server, village.dimension());
        if (level == null || level.getChunkSource().getChunkNow(village.buildingOrigin().getX() >> 4,
                village.buildingOrigin().getZ() >> 4) == null) return;
        var growth = SettlementGrowthData.get(server);
        if (!growth.authority(server, village)) { row.putString("Reason", "NO_AUTHORITY"); return; }
        var house = job(row, "HOUSE");
        // 未领料、未施工才可采用已有合法木板的有限替代蓝图，不拆换旧半成品。
        if (!house.contains("WallMaterial") && house.getIntOr("Placed", 0) == 0 && !house.getBooleanOr("MaterialCarried", false)) {
            int planks = available(server, village, 1, Items.OAK_PLANKS) + available(server, village, 3, Items.OAK_PLANKS);
            int parts = (done(row, "BED") ? 0 : 3) + (done(row, "DOOR") ? 0 : 6);
            if (planks >= 55 + parts) {
                house.putString("WallMaterial", "WOOD");
                house.putLong("BlueprintSelectedAt", now);
                house.putString("BlueprintReason", "合法现有木板足以覆盖墙体和未完部件，采用同尺寸有限蓝图");
            }
        }
        double usable = available(server, village, 1, Items.CARROT);
        // RAW 的留种保护不等于运输预留；同用途重叠只在原种粮规则已证明时采用。
        usable += availableProtected(server, village, 0, Items.CARROT, NaturalFarmRules.SEEDS);
        boolean emergency = NaturalFarmRules.severelyHungry(server, village);
        row.putDouble("UsableCarrots", usable);
        var household = HouseholdData.get(server).byId(NaturalVillageLifeData.home(village));
        var people = com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(server);
        // Use existing identity facts, never entity presence. Unknown records remain conservatively counted.
        long residents = household == null ? 3 : household.members().stream().filter(member -> {
            var person = people.byId(member.characterId());
            return person == null || person.alive();
        }).count();
        row.putDouble("DailyMeals", residents * SettlementPolicy.DAILY_MEALS_PER_PERSON);
        row.putBoolean("Harvest", residents > 0 && SettlementPolicy.harvest(row.getBooleanOr("Harvest", false), usable,
                row.getDoubleOr("DailyMeals", 0.0), emergency));
        row.putDouble("FoodDays", residents == 0 ? 0 : usable / row.getDoubleOr("DailyMeals", 0.0));
        if (row.getDoubleOr("FoodDays", 0.0) < SettlementPolicy.LOW_DAYS) {
            if (!row.contains("LowFoodSince")) row.putLong("LowFoodSince", now);
            if (now - row.getLongOr("LowFoodSince", 0L) >= 24000) row.putBoolean("ExpandFields", true);
        } else row.remove("LowFoodSince");
        row.putLong("Observed", now);
        var owner = SubjectRef.household(NaturalVillageLifeData.home(village));
        var flows = SupplyFlowData.get(server);
        int remaining = Math.max(0, 55 - Math.min(55, job(row, "HOUSE").getIntOr("Placed", 0))
                - available(server, village, 3, houseMaterial(row))
                - (job(row, "STONE").getBooleanOr("Cargo", false) ? job(row, "STONE").getIntOr("Quantity", 0) : 0)
                - (job(row, "HOUSE").getBooleanOr("MaterialCarried", false) ? 1 : 0));
        updateMaterialPolicy(server, village, "growth-stone:", Items.COBBLESTONE,
                0, now); // Functional masonry is supplied locally; retain existing committed routes, stop new concrete-only requests.
        if (houseMaterial(row) == Items.OAK_PLANKS) updateMaterialPolicy(server, village,
                "growth-planks:", Items.OAK_PLANKS, remaining, now);
        updateMaterialPolicy(server, village, "growth-timber:", Items.OAK_LOG, done(row, "LOGS") ? 0 : 3, now);
        row.putInt("StoneDeficit", stoneDeficit(server, row));
        if (done(row, "HOUSE")) {
            if (!done(row, "STONE") && !job(row, "STONE").getBooleanOr("Cargo", false)) {
                complete(job(row, "STONE"), now);
                job(row, "STONE").putString("Reason", "工程实物预算已满足，本任务无待交材料；原运输义务仍归运输账本");
            }
            var work = FieldworkData.get(server);
            var body = NaturalVillageLifeData.body(village, 2);
            boolean executing = com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData.get(server).active(body)
                    .filter(plan -> plan.providerId().equals(FieldworkPlanFactory.PROVIDER)
                            && !plan.currentStep().executorId().equals(FieldworkPlanFactory.REST)).isPresent();
            if (!done(row, "SURVEY") && !executing && work.pendingCargo(body).isEmpty() && work.pendingDelivery(body).isEmpty()) {
                complete(job(row, "SURVEY"), now);
                job(row, "SURVEY").putString("Reason", "原资源缺口已实际满足且采掘产出已入库，保留发现和劳动历史");
            }
        }
        growth.maintainEntrance(server, level, village, job(row, "HOUSE"), now);
        setDirty();
    }

    private static void updateMaterialPolicy(MinecraftServer server, NaturalVillageBuilding village,
                                             String prefix, Item material, int target, long now) {
        String id = prefix + village.buildingKey();
        var flows = SupplyFlowData.get(server);
        var owner = SubjectRef.household(NaturalVillageLifeData.home(village));
        if (target > 0) {
            var policy = new SupplyFlowData.Policy(id, owner, NaturalVillageEconomyData.storeId(village, 1),
                    item(material), target, "household");
            var old = flows.policies().stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
            if (old != null && !old.equals(policy)) {
                if (!old.principal().equals(policy.principal()) || !old.destination().equals(policy.destination())
                        || !old.item().equals(policy.item()) || !old.category().equals(policy.category())) {
                    throw new IllegalStateException("协调政策的原权利主体或目标发生变化，需核对");
                }
                // 原接口把整个定义视为不可变；先撤销旧目标再发布新目标，保留原需求与路线。
                flows.removePolicy(id);
            }
            flows.policy(policy);
        } else {
            // 真实工程预算已满足才停止新增采购；原预留和在途量由领域方法保留。
            var needs = NeedRequestData.get(server);
            for (var need : needs.requests()) {
                if (need.key().purpose().equals(owner.type() + ":household:" + id)
                        && !need.status().terminal() && need.status() != NeedRequestStatus.DELIVERED) {
                    needs.reconcileStockDeficit(need.id(), 0, now);
                }
            }
            flows.removePolicy(id);
        }
    }

    public static boolean ordinaryFieldwork(NpcBase npc) {
        if(npc.getProfession()==NpcProfession.WORKER&&SettlementResourceStock.applies(npc))return SettlementResourceStock.needsAny(npc)||SettlementDevelopmentData.ordinaryWorker(npc);
        if(SettlementDevelopmentData.farmer(npc))return SettlementDevelopmentData.harvest(npc)||SettlementDevelopmentData.needsFieldPreparation(npc);
        if(SettlementDevelopmentData.residentVillage(MinecraftApi.server(npc),npc.getCharacterId())!=null && npc.getProfession()==NpcProfession.WORKER && SettlementDevelopmentData.ordinaryWorker(npc))return true;
        var data = existing(MinecraftApi.server(npc));
        var row = data == null ? null : data.resident(npc.getCharacterId());
        if (row == null) return true;
        if (npc.getProfession() == NpcProfession.FARMER) return row.getBooleanOr("Harvest", false);
        return npc.getProfession() == NpcProfession.WORKER
                && (!done(row, "LOGS") || data.stoneDeficit(MinecraftApi.server(npc), row) > 0);
    }

    public static boolean resourceAllowed(NpcBase npc, BlockPos position) {
        if(SettlementDevelopmentData.resourceAllowed(npc,position))return true;
        var data = existing(MinecraftApi.server(npc));
        var row = data == null ? null : data.resident(npc.getCharacterId());
        return row != null && SettlementGrowthData.get(MinecraftApi.server(npc)).authority(MinecraftApi.server(npc), building(row))
                && SettlementResourceSurvey.allowed(npc, row, position);
    }

    public static boolean needsBoundedNavigation(NpcBase npc, BlockPos target) {
        if (MinecraftApi.server(npc) == null) return false;
        // Admitted households use the same physical obstacle planner as founders.
        // This is routing only; work sites and item rights retain separate checks.
        if(SettlementDevelopmentData.residentVillage(MinecraftApi.server(npc),npc.getCharacterId())!=null)return true;
        var data = existing(MinecraftApi.server(npc));
        var row = data == null ? null : data.resident(npc.getCharacterId());
        if (row == null) return false;
        var village = building(row);
        var a = NaturalVillageLayout.at(village, BlockPos.ZERO);
        var b = NaturalVillageLayout.at(village, new BlockPos(25, 0, 21));
        for (var position : new BlockPos[]{npc.blockPosition(), target}) {
            if (position.getX() < Math.min(a.getX(), b.getX()) || position.getX() > Math.max(a.getX(), b.getX())
                    || position.getZ() < Math.min(a.getZ(), b.getZ()) || position.getZ() > Math.max(a.getZ(), b.getZ())) return true;
        }
        return false;
    }

    public static boolean extraField(NpcBase npc, BlockPos position) {
        var data = existing(MinecraftApi.server(npc));
        var row = data == null ? null : data.resident(npc.getCharacterId());
        if (row == null || !row.getBooleanOr("ExpandFields", false) || npc.getProfession() != NpcProfession.FARMER) return false;
        return GrowthLayout.extension().stream().map(local -> NaturalVillageLayout.at(building(row), local))
                .anyMatch(point -> point.getX() == position.getX() && point.getZ() == position.getZ());
    }

    public Optional<NpcPlan> choose(NpcBase npc, long now) {
        var row = resident(npc.getCharacterId());
        if (row == null || row.getBooleanOr("Review", false)) return Optional.empty();
        var work = FieldworkData.get(MinecraftApi.server(npc));
        // 原携货先沿旧交付计划安置；不能被新项目抢走。
        if (FieldworkDepositTaskExecutor.hasDeliverableCargo(npc, work) && work.deliveryRetryAt(npc.getUUID()) <= now) return Optional.of(
                FieldworkPlanFactory.deposit(npc.getUUID(), now));
        if (!SettlementGrowthData.get(MinecraftApi.server(npc)).eligible(npc, row)) return Optional.empty();
        if (npc.getProfession() == NpcProfession.FARMER && row.getBooleanOr("Harvest", false)) return Optional.empty();
        var candidates = new ArrayList<SettlementPolicy.Candidate>();
        for (String kind : row.getCompoundOrEmpty("Jobs").keySet()) {
            var task = job(row, kind);
            boolean ours = !task.read("Person", UUIDUtil.CODEC).isPresent() || task.read("Person", UUIDUtil.CODEC).orElseThrow().equals(npc.getCharacterId());
            boolean ready = ours && !task.getBooleanOr("Review", false) && now >= task.getLongOr("RetryAt", 0L)
                    && feasible(npc, row, task);
            int tier = List.of("LOGS", "PLANKS", "WOOL", "BED", "TEMP_BED").contains(kind) ? 1
                    : kind.equals("SURVEY") ? 3 : 4;
            if (task.getBooleanOr("Cargo", false) || task.getBooleanOr("MaterialCarried", false)) tier = 2;
            double effort = npc.distanceToSqr(NaturalVillageLayout.at(building(row), NaturalVillageLayout.PUBLIC).getCenter());
            candidates.add(new SettlementPolicy.Candidate(kind, tier, task.getLongOr("Created", 0L),
                    Long.MAX_VALUE, effort, kind.equals("SURVEY") ? 2 : 1, ready));
        }
        var candidate = SettlementPolicy.choose(candidates);
        if (candidate == null) return Optional.empty();
        var task = job(row, candidate.id());
        if (candidate.id().equals("SURVEY")) {
            var mining = SettlementResourceSurvey.mining(npc, row, now);
            if (mining.isPresent()) {
                task.store("FieldworkPlan", UUIDUtil.CODEC, mining.get().id());
                task.putString("Reason", "已发现的合法资源交原采掘执行器");
                setDirty();
                return mining;
            }
        }
        task.store("Person", UUIDUtil.CODEC, npc.getCharacterId());
        task.store("Body", UUIDUtil.CODEC, npc.getUUID());
        task.putString("State", "CLAIMED");
        var plan = SettlementCoordinatorTask.plan(npc, candidate.id(), now);
        task.store("Plan", UUIDUtil.CODEC, plan.id());
        setDirty();
        return Optional.of(plan);
    }

    private boolean feasible(NpcBase npc, CompoundTag row, CompoundTag task) {
        if (task.getStringOr("State", "").equals("DONE")) return false;
        if (task.getBooleanOr("Cargo", false) || task.getBooleanOr("MaterialCarried", false)) return true;
        var server = MinecraftApi.server(npc);
        var village = building(row);
        return switch (task.getStringOr("Kind", "")) {
            case "LOGS" -> available(server, village, 1, Items.OAK_LOG) >= 3;
            case "PLANKS" -> done(row, "LOGS");
            case "BED" -> done(row, "PLANKS") && (available(server,village,3,Items.WHITE_WOOL)>=3
                    || SettlementGrowthData.canResumeReservedBed(npc, task));
            case "WOOL" -> com.jgeted.sagadyssey.social.industry.common.SettlementIndustryAccess.resident(npc)
                    && com.jgeted.sagadyssey.social.industry.animal.WoolSupplyNeeds.publicWoolDeficit(server,village)>0
                    && available(server,village,1,Items.WHITE_WOOL)
                        +available(server,village,2,Items.WHITE_WOOL)>0;
            case "TEMP_BED" -> done(row, "BED");
            case "DOOR" -> done(row, "PLANKS");
            case "STONE" -> (houseMaterial(row)==Items.COBBLESTONE?(SettlementMaterials.available(server,village,1)+SettlementMaterials.available(server,village,2)):(available(server,village,1,houseMaterial(row))+available(server,village,2,houseMaterial(row)))) > 0
                    && (houseMaterial(row)==Items.COBBLESTONE?SettlementMaterials.available(server,village,3):available(server,village,3,houseMaterial(row))) < 55 - Math.min(55, job(row, "HOUSE").getIntOr("Placed", 0));
            case "HOUSE" -> {
                int index = task.getIntOr("Placed", 0);
                yield index >= 57 || index < 55 && (houseMaterial(row)==Items.COBBLESTONE?SettlementMaterials.available(server,village,3):available(server,village,3,houseMaterial(row))) > 0
                        || index == 55 && done(row, "DOOR") || index == 56 && done(row, "RELOCATE");
            }
            case "RELOCATE" -> done(row, "TEMP_BED") && job(row, "HOUSE").getIntOr("Placed", 0) >= 56;
            case "SURVEY" -> npc.getProfession() == NpcProfession.WORKER && done(row, "LOGS")
                    && (stoneDeficit(server,row)>0||SettlementResourceStock.applies(npc)&&SettlementResourceStock.needsAny(npc)) && SettlementResourceSurvey.needsSurvey(npc,row);
            default -> false;
        };
    }

    public String work(NpcBase npc, String kind, UUID planId, long now) {
        var row = resident(npc.getCharacterId());
        if (row == null) return "YIELD";
        var task = job(row, kind);
        if (done(row, kind)) return "YIELD";
        if (!task.read("Person", UUIDUtil.CODEC).isPresent() || !task.read("Person", UUIDUtil.CODEC).orElseThrow().equals(npc.getCharacterId())
                || !task.read("Body", UUIDUtil.CODEC).orElseThrow().equals(npc.getUUID()) || !task.read("Plan", UUIDUtil.CODEC).orElseThrow().equals(planId)
                || !SettlementGrowthData.get(MinecraftApi.server(npc)).eligible(npc, row)) return "YIELD";
        if (task.getLongOr("LastTick", 0L) == now) return "同刻不重复劳动";
        task.putLong("LastTick", now);
        task.putString("State", "WORKING");
        var growth = SettlementGrowthData.get(MinecraftApi.server(npc));
        int beforeWork = task.getIntOr("Work", 0), beforePlaced = task.getIntOr("Placed", 0);
        String result;
        switch (kind) {
            case "LOGS", "STONE", "WOOL" -> result = moveMaterial(npc, row, task, now);
            case "PLANKS", "BED", "DOOR" -> {
                result = growth.craft(npc, task, kind);
                if (!task.getStringOr("HouseStage", "").equals(kind)) complete(task, now);
            }
            case "TEMP_BED" -> result = temporaryBed(npc, growth, building(row), task, now);
            case "HOUSE" -> {
                if (!task.contains("WallMaterial")) task.putString("WallMaterial", "STONE");
                result = growth.build(npc, task);
                if (task.getStringOr("HouseStage", "").equals("DONE")) complete(task, now);
            }
            case "RELOCATE" -> result = SettlementBedRelocation.work(npc, row, task, now);
            case "SURVEY" -> result = SettlementResourceSurvey.work(npc, row, task, now);
            default -> result = "YIELD";
        }
        if (task.getIntOr("Work", 0) != beforeWork || task.getIntOr("Placed", 0) != beforePlaced) task.putLong("LastProgress", now);
        if (!result.equals("YIELD")) task.putString("Reason", result);
        if (result.equals("YIELD")) task.putLong("RetryAt", now + 200);
        setDirty();
        return task.getStringOr("State", "").equals("DONE") ? "YIELD" : result;
    }

    static String temporaryBed(NpcBase npc, SettlementGrowthData growth, NaturalVillageBuilding village,
                               CompoundTag task, long now) {
        var bed = SettlementBedRelocation.temporary(village);
        String result = task.getIntOr("Placed", 0) == 0 ? growth.place(npc, task, bed, 0) : "YIELD";
        if (task.getIntOr("Placed", 0) != 1) return result;
        if (!SettlementBedRelocation.originalBedPresent((ServerLevel) npc.level(), bed)) {
            task.putString("Reason", "已施工的临时床缺失、变更或未加载，保留原放置责任");
            return "YIELD";
        }
        if (NpcLifeData.get(MinecraftApi.server(npc)).assignBed(NaturalVillageLifeData.person(village, 2),
                village.dimension(), bed.positions().getLast(), "已有安全屋内的真实临时床", now)) {
            complete(task, now);
        } else {
            task.putString("Reason", "临时床归属交接被拒绝，保留原施工结果并等待重检");
        }
        return "YIELD";
    }

    static void complete(CompoundTag task, long now) {
        task.putString("State", "DONE");
        task.putLong("Completed", now);
        task.putLong("LastProgress", now);
    }

    void yield(NpcBase npc, String kind, UUID plan, long now) {
        var row = resident(npc.getCharacterId());
        if (row == null) return;
        var task = job(row, kind);
        if (!task.read("Plan", UUIDUtil.CODEC).isPresent() || !task.read("Plan", UUIDUtil.CODEC).orElseThrow().equals(plan) || done(row, kind)) return;
        task.putString("State", "WAITING");
        task.putLong("LastYield", now);
        setDirty();
    }

    private String moveMaterial(NpcBase npc, CompoundTag row, CompoundTag task, long now) {
        var village = building(row);
        var growth = SettlementGrowthData.get(MinecraftApi.server(npc));
        Item type = task.getStringOr("Kind", "").equals("WOOL") ? Items.WHITE_WOOL
                : task.getStringOr("Kind", "").equals("LOGS") ? Items.OAK_LOG : houseMaterial(row)==Items.COBBLESTONE
                ? task.getBooleanOr("Cargo", false)?SettlementMaterials.carried(MinecraftApi.server(npc),task,"Pickup",Items.COBBLESTONE,ContainerIds.npcPersonal(npc.getCharacterId())):SettlementMaterials.choose(MinecraftApi.server(npc),village,1,2)
                :houseMaterial(row);
        if(type==Items.AIR){if(task.getBooleanOr("Cargo", false))task.putBoolean("Review",true);return "没有可核实的具体材料，保留原携货与责任";}
        UUID personal = ContainerIds.npcPersonal(npc.getCharacterId());
        var character = SubjectRef.character(npc.getCharacterId());
        RightsStateData.get(MinecraftApi.server(npc)).ensureContext(personal, "npc:" + npc.getCharacterId() + ":personal",
                character, character, AccessPolicy.PRIVATE);
        if (!task.getBooleanOr("Cargo", false)) {
            int sourceIndex=available(MinecraftApi.server(npc),village,1,type)>0?1:2;
            if (!growth.near(npc, NaturalVillageLayout.at(village, NaturalVillageLayout.stores(village).get(sourceIndex)))) return "前往领取合法材料";
            int quantity = type == Items.OAK_LOG ? 3 : Math.min(16, available(MinecraftApi.server(npc), village, sourceIndex, type));
            if (type == Items.WHITE_WOOL) quantity = Math.min(quantity,
                    com.jgeted.sagadyssey.social.industry.animal.WoolSupplyNeeds.publicWoolDeficit(MinecraftApi.server(npc),village));
            else if (type != Items.OAK_LOG) quantity = Math.min(quantity,
                    Math.max(0, 55 - Math.min(55, job(row, "HOUSE").getIntOr("Placed", 0))
                            - (houseMaterial(row)==Items.COBBLESTONE?SettlementMaterials.available(MinecraftApi.server(npc),village,3):available(MinecraftApi.server(npc),village,3,type))
                            - (job(row, "HOUSE").getBooleanOr("MaterialCarried", false) ? 1 : 0)));
            if (quantity == 0) return "YIELD";
            UUID source = NaturalVillageEconomyData.storeId(village, sourceIndex);
            UUID transaction = ContainerIds.named(task.getStringOr("Id", "") + ":pickup:" + task.getIntOr("Cycle", 0));
            if (!growth.transfer(npc, task, transaction, SubjectRef.household(NaturalVillageLifeData.home(village)),
                    TransferIntent.GIFT, List.of(new TransferLeg(source, personal, item(type), quantity)),
                    Map.of(source, store(MinecraftApi.server(npc), village, sourceIndex), personal, npc.getPersonalInventory()))) return "YIELD";
            task.putBoolean("Cargo", true);
            task.putInt("Quantity", quantity);
            task.store("Pickup", UUIDUtil.CODEC, task.read("LastTransfer", UUIDUtil.CODEC).orElseThrow());
            task.putLong("LastProgress", now);
            return "材料已进入原协作者背包，接续实际搬运";
        }
        if (!growth.near(npc, NaturalVillageLayout.at(village, NaturalVillageLayout.PUBLIC))) return "携带材料前往公共仓";
        UUID target = NaturalVillageEconomyData.storeId(village, 3);
        UUID transaction = ContainerIds.named(task.getStringOr("Id", "") + ":deposit:" + task.getIntOr("Cycle", 0));
        if (!growth.transfer(npc, task, transaction, character, TransferIntent.GIFT,
                List.of(new TransferLeg(personal, target, item(type), task.getIntOr("Quantity", 0))),
                Map.of(personal, npc.getPersonalInventory(), target, store(MinecraftApi.server(npc), village, 3)))) return "YIELD";
        task.store("Delivery", UUIDUtil.CODEC, task.read("LastTransfer", UUIDUtil.CODEC).orElseThrow());
        task.remove("Cargo");
        task.putInt("Cycle", task.getIntOr("Cycle", 0) + 1);
        task.putLong("LastProgress", now);
        if (type == Items.OAK_LOG) complete(task, now);
        return "材料已真实入公共仓，保留两段转移回执";
    }

    @SubscribeEvent
    public static void placed(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        protectEditedPosition(level, event.getPos());
    }

    @SubscribeEvent
    public static void broken(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) protectEditedPosition(level, event.getPos());
    }

    private static void protectEditedPosition(ServerLevel level, BlockPos position) {
        var data = existing(level.getServer());
        if (data == null) return;
        for (var row : data.sites.values()) {
            if (building(row).dimension().equals(level.dimension().identifier().toString())
                    && (SettlementResourceSurvey.inParcel(row, position)
                    || SettlementResourceSurvey.exitClearanceCell(building(row), position))) {
                row.getCompoundOrEmpty("ProtectedEdits").putLong(Long.toString(position.asLong()), level.getGameTime());
                data.setDirty();
            }
        }
    }

    @SubscribeEvent
    public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % 200 != 0) return;
        var growth = SettlementGrowthData.existing(server);
        if (growth == null) return;
        for (var site : growth.sites()) {
            var village = building(site);
            if (!GrowthLayout.COORDINATED.toString().equals(village.templateId()) && !SettlementDevelopmentLayout.supports(village)) continue;
            var home = HouseholdData.get(server).byId(NaturalVillageLifeData.home(village));
            if (home != null) get(server).activate(server, village.buildingKey(), home.headCharacterId(), now);
        }
        var data = existing(server);
        if (data != null) for (var row : data.sites.values()) data.assess(server, row, now);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putInt("Schema", 1);
        var list = new ListTag();
        sites.values().forEach(row -> list.add(row.copy()));
        tag.put("Sites", list);
        return tag;
    }

    private static SettlementCoordinatorData load(CompoundTag tag, HolderLookup.Provider lookup) {
        if (tag.getIntOr("Schema", 0) != 1) throw new IllegalArgumentException("Unknown coordinator schema");
        var data = new SettlementCoordinatorData();
        for (var value : MinecraftApi.listOfType(tag, "Sites", Tag.TAG_COMPOUND)) {
            var row = ((CompoundTag) value).copy();
            if (row.getIntOr("PolicyVersion", 0) != SettlementPolicy.VERSION) throw new IllegalArgumentException("Unknown policy");
            for (String key : row.getCompoundOrEmpty("Jobs").keySet()) {
                var task = job(row, key);
                if (task.getBooleanOr("Submitting", false)) {
                    task.putBoolean("Review", true);
                    task.putString("Reason", "提交中断，原回执和物资需核对，不重放或清账");
                }
            }
            if (data.sites.putIfAbsent(building(row).buildingKey(), row) != null) throw new IllegalArgumentException("Duplicate site");
        }
        return data;
    }
}
