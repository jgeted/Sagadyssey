package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.logistics.*;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import java.util.*;

/** 持久携带 → 统一转移入库。目的地满/失效时结束本轮并进入有限替代计划，产出不消失。 */
public final class FieldworkDepositTaskExecutor implements NpcTaskExecutor {
    private final Map<UUID, MovementProgress> progress = new HashMap<>();
    @Override public void forget(UUID body,UUID plan){progress.remove(plan);}
    @Override public Identifier id() { return FieldworkPlanFactory.DEPOSIT; }
    @Override public TaskTickResult start(NpcExecutionContext c, NpcPlan p, NpcPlanStep s) { return deposit(c, p); }
    @Override public TaskTickResult tick(NpcExecutionContext c, NpcPlan p, NpcPlanStep s) { return deposit(c, p); }
    @Override public TaskTickResult recover(NpcExecutionContext c, NpcPlan p, NpcPlanStep s) { progress.remove(p.id()); return deposit(c, p); }
    @Override public void cancel(NpcExecutionContext c, NpcPlan p, NpcPlanStep s, PlanCancellationReason reason) {
        progress.remove(p.id()); if (c instanceof NpcEntityExecutionContext context) context.npc().getNavigation().stop();
    }

    private TaskTickResult deposit(NpcExecutionContext raw, NpcPlan plan) {
        if (!(raw instanceof NpcEntityExecutionContext c)) return TaskTickResult.failed("入库需要服务端实体");
        NpcBase npc = c.npc(); var server = c.level().getServer(); long now = c.gameTime();
        FieldworkData data = FieldworkData.get(server);
        var pending = data.pendingDelivery(npc.getUUID()).orElse(null);
        // 即使目的地已被拆除，已成功的统一转移回执仍足以结束同一交付意图。
        if (pending != null) {
            TransferReceipt receipt = TransferLedgerData.get(server).receipt(pending.transferId());
            if (receipt != null) {
                finish(data, pending, receipt);
                if (!receipt.successful()) return defer(c, plan, "交付条件已变化，稍后重新选择目的地");
                pending = null;
            }
        }
        if (!data.outputConsumptions(npc.getUUID()).isEmpty()) {
            String consumptionReview = data.outputConsumptionReview(npc.getUUID(),
                    com.jgeted.sagadyssey.npc.brain.life.LifeActivityData.get(server)::settlement);
            if (!consumptionReview.isEmpty()) return defer(c, plan, consumptionReview);
        }
        Map<String, Integer> cargo = data.pendingCargo(npc.getUUID());
        ItemStack sample = sample(npc, cargo);
        if (pending == null && sample.isEmpty()) {
            progress.remove(plan.id()); return TaskTickResult.stepSucceeded("没有可交付产出，种子和工作投入留在随身库存");
        }
        FieldworkStorage storage = pending == null ? selectStorage(npc, data) : pending.storage();
        if (storage == null || !storage.dimension().equals(c.level().dimension().identifier().toString())
                || !c.level().hasChunkAt(storage.position()) || !authorized(npc, storage, data)) {
            return defer(c, plan, "没有当前可用且有权限的入库目的地，保留产出并休整");
        }
        Container target = WarehouseContainers.asContainer(c.level().getBlockEntity(storage.position()));
        if (target == null) return defer(c, plan, "入库容器已失效，保留产出并休整");
        if (!FieldworkRoutes.closeEnough(npc, storage.position())) {
            var route = FieldworkRoutes.plan(npc, storage.position(), false);
            if (route == null) return defer(c, plan, "入库道路暂不可达，保留产出并休整");
            var movement = progress.computeIfAbsent(plan.id(), ignored -> { var value = new MovementProgress(); value.reset(npc, route.stand()); return value; });
            if (movement.tick(npc, route.stand())) return defer(c, plan, "入库道路无法推进，保留产出并休整");
            if (now % 20 == 0 || npc.getNavigation().isDone()) {
                if (!FieldworkRoutes.move(npc, route.stand())) return defer(c, plan, "入库道路暂不可达，保留产出并休整");
            }
            npc.reportWorkActivity("搬运收获与材料", null, "正在前往储物处");
            return TaskTickResult.running("携带真实产出，前往授权储物处");
        }
        npc.getNavigation().stop();
        RightsStateData rights = RightsStateData.get(server);
        SubjectRef actor = SubjectRef.character(npc.getCharacterId()); UUID sourceId = ContainerIds.npcPersonal(npc.getCharacterId());
        rights.ensureContext(sourceId, "npc:" + npc.getCharacterId() + ":personal", actor, actor, AccessPolicy.PRIVATE);
        rights.ensureContext(storage.containerId(), storage.dimension() + ':' + storage.position().asLong(), storage.owner(), storage.owner(), storage.policy());
        if (pending == null) {
            int amount = Math.min(cargo.getOrDefault(FieldworkWorldMutation.fingerprint(sample), 0),
                    Math.min(transferable(npc, sample), capacity(target, sample)));
            if (amount <= 0) return defer(c, plan, "储物处已满，保留产出并整理工具");
            pending = new FieldworkStorage.Pending(npc.getUUID(), sourceId, actor, storage, UUID.randomUUID(),
                    ItemFingerprint.of(sample), amount, rights.context(sourceId).revision(),
                    rights.context(storage.containerId()).revision(), now);
            data.beginDelivery(pending);
        }
        var receipt = new TransferService(rights, TransferLedgerData.get(server)).submit(pending.request(),
                Map.of(sourceId, new TransferEndpoint(sourceId, npc.getPersonalInventory()),
                        storage.containerId(), new TransferEndpoint(storage.containerId(), target)), now);
        finish(data, pending, receipt);
        if (!receipt.successful()) return defer(c, plan, "交付尚未提交，保留产出并重新核验权限与容量");
        npc.playAction(NpcAction.TRADE_PRESENT, 20);
        npc.reportWorkActivity("把劳动产出放入储物处", storage.position(), "本轮交付完成");
        return TaskTickResult.running("完成一批统一转移，核验剩余携带物");
    }

    public static void finish(FieldworkData data, FieldworkStorage.Pending pending, TransferReceipt receipt) {
        if (!pending.transferId().equals(receipt.transferId())) throw new IllegalStateException("wrong delivery receipt");
        if (receipt.successful()) data.delivered(new FieldworkCargo.Delivery(pending.transferId(), pending.npcId(),
                pending.fingerprint().itemId() + '#' + pending.fingerprint().componentsHash(), pending.quantity(), receipt.completedAt()));
        data.finishDelivery(pending.npcId(), pending.transferId());
    }

    private TaskTickResult defer(NpcEntityExecutionContext c, NpcPlan plan, String reason) {
        progress.remove(plan.id()); c.npc().getNavigation().stop();
        FieldworkData.get(c.level().getServer()).deferDelivery(c.npcId(), c.gameTime() + 200);
        c.npc().reportWorkActivity("保管产出并整理工具", null, reason);
        return TaskTickResult.stepSucceeded(reason);
    }

    private static ItemStack sample(NpcBase npc, Map<String, Integer> cargo) {
        for (int slot = 0; slot < npc.getPersonalInventory().getContainerSize(); slot++) {
            ItemStack stack = npc.getPersonalInventory().getItem(slot);
            if (!stack.isEmpty() && cargo.getOrDefault(FieldworkWorldMutation.fingerprint(stack), 0) > 0
                    && transferable(npc, stack) > 0) return stack.copy();
        }
        return ItemStack.EMPTY;
    }
    public static boolean hasDeliverableCargo(NpcBase npc, FieldworkData data) {
        return data.pendingDelivery(npc.getUUID()).isPresent() || !sample(npc, data.pendingCargo(npc.getUUID())).isEmpty();
    }
    private static int transferable(NpcBase npc, ItemStack sample) {
        int count = npc.getPersonalInventory().count(stack -> ItemStack.isSameItemSameComponents(stack, sample));
        if(com.jgeted.sagadyssey.social.industry.forestry.ForestrySeedPolicy.isRenewalSeed(sample)
                && com.jgeted.sagadyssey.social.industry.forestry.TreePlantTask.enabled(npc)) count-=1;
        if (npc.getProfession() == NpcProfession.FARMER) {
            if (sample.is(Items.WHEAT_SEEDS) || sample.is(Items.CARROT) || sample.is(Items.POTATO) || sample.is(Items.BEETROOT_SEEDS))
                count -= sample.is(Items.CARROT)?com.jgeted.sagadyssey.core.structure.NaturalFarmRules.seedProtection(npc):16;
            if (sample.is(Items.MELON_SEEDS) || sample.is(Items.PUMPKIN_SEEDS)) count -= 4;
            if (sample.is(Items.COCOA_BEANS) || sample.is(Items.SUGAR_CANE)) count -= 8;
        }
        return Math.max(0, count);
    }
    public static int capacity(Container container, ItemStack sample) {
        int capacity = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, sample)) continue;
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, sample)) {
                capacity += Math.max(0, Math.min(container.getMaxStackSize(), sample.getMaxStackSize()) - stack.getCount());
            }
        }
        return capacity;
    }
    /** Read the same destination used by actual deposits; grants no rights or title. */
    public static FieldworkStorage storageFor(NpcBase npc, FieldworkData data) { return selectStorage(npc,data); }

    private static FieldworkStorage selectStorage(NpcBase npc, FieldworkData data) {
        var configured = data.testStorage(npc.getUUID()).orElse(null);
        if (configured != null) return configured;
        var server = MinecraftApi.server(npc); String dimension = npc.level().dimension().identifier().toString();
        var warehouse = LogisticsData.get(server).warehouses().stream().filter(value -> value.dimension().equals(dimension))
                .filter(value -> NpcWorkContext.from(npc).canUseWarehouse(server, value))
                .min(Comparator.comparingDouble(value -> npc.blockPosition().distSqr(value.position()))).orElse(null);
        if (warehouse != null) return new FieldworkStorage(dimension, warehouse.position(), warehouse.id(),
                SubjectRef.institution(warehouse.settlementId()), AccessPolicy.INSTITUTION, TransferIntent.DELIVERY, "work_output:" + warehouse.settlementId());
        var life = NpcLifeData.get(server).record(npc.getCharacterId());
        if (life != null && life.dimension().equals(dimension) && life.privateStoragePosition() != null) {
            BlockPos pos = life.privateStoragePosition();
            return new FieldworkStorage(dimension, pos, ContainerIds.block("private_storage", dimension, pos),
                    SubjectRef.character(npc.getCharacterId()), AccessPolicy.PRIVATE, TransferIntent.DELIVERY, "personal_storage");
        }
        var household = HouseholdData.get(server).forMember(npc.getCharacterId());
        if (household != null && household.sharedStorage() != null) {
            BlockPos pos = household.sharedStorage();
            return new FieldworkStorage(dimension, pos, ContainerIds.block("household_storage", dimension, pos),
                    SubjectRef.household(household.id()), AccessPolicy.HOUSEHOLD, TransferIntent.GIFT, "household_contribution:" + household.id());
        }
        return null;
    }
    private static boolean authorized(NpcBase npc, FieldworkStorage storage, FieldworkData data) {
        if (data.testStorage(npc.getUUID()).filter(storage::equals).isPresent()) {
            return storage.owner().equals(SubjectRef.character(npc.getCharacterId()));
        }
        return storage.equals(selectStorage(npc, data));
    }
}
