package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.household.HouseholdRecord;
import com.jgeted.sagadyssey.social.work.NpcWorkContext;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.ExpectedRevision;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 将生产职业背包中的实体产出送进附近已登记仓库。 */
public final class NpcWarehouseDepositGoal extends Goal {
    private static final double SEARCH_RANGE = 64.0D;
    private static final double INTERACTION_RANGE_SQ = 9.0D;
    private static final int WORKER_SCAFFOLD_RESERVE = 8;

    private final NpcBase npc;
    @Nullable private WarehouseRecord warehouse;
    @Nullable private BlockPos privateStorage;
    @Nullable private BlockPos householdStorage;
    @Nullable private UUID householdId;
    @Nullable private BlockPos approach;
    private int recalcPath;
    private int pathFailures;
    private final MovementProgress movementProgress = new MovementProgress();

    public NpcWarehouseDepositGoal(NpcBase npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive() || npc.isPassenger()
                || npc.getCommand() != NpcCommand.WORK || npc.getTarget() != null
                || !isProducer(npc.getProfession()) || !NpcSchedulePlanner.allowsWork(npc)
                || (npc.tickCount - npc.getId()) % 100 != 0) return false;
        int outputs = outputCount();
        if (outputs <= 0 || (outputs < 4 && npc.getBagFreeSlots() > 2)) return false;
        warehouse = LogisticsData.get(level.getServer()).warehouses().stream()
                .filter(record -> record.dimension().equals(level.dimension().identifier().toString()))
                .filter(record -> canUseWarehouse(level, record))
                .filter(record -> record.position().distSqr(npc.blockPosition()) <= SEARCH_RANGE * SEARCH_RANGE)
                .filter(record -> level.hasChunkAt(record.position())
                        && WarehouseContainers.asContainer(level.getBlockEntity(record.position())) != null)
                .min(Comparator.comparingDouble(record -> record.position().distSqr(npc.blockPosition())))
                .orElse(null);
        privateStorage = null;
        householdStorage = null;
        householdId = null;
        if (warehouse == null) {
            NpcLifeRecord life = NpcLifeData.get(level.getServer()).record(npc.getCharacterId());
            BlockPos personal = life == null ? null : life.privateStoragePosition();
            HouseholdRecord household = HouseholdData.get(level.getServer()).forMember(npc.getCharacterId());
            BlockPos shared = household == null ? null : household.sharedStorage();
            boolean contributionTurn = Math.floorMod(Math.floorDiv(npc.tickCount, 100)
                    + npc.getId(), 4) == 0;
            if (contributionTurn && shared != null
                    && shared.distSqr(npc.blockPosition()) <= SEARCH_RANGE * SEARCH_RANGE
                    && level.hasChunkAt(shared)
                    && WarehouseContainers.asContainer(level.getBlockEntity(shared)) != null) {
                householdStorage = shared;
                householdId = household.id();
            }
            if (personal != null && personal.distSqr(npc.blockPosition()) <= SEARCH_RANGE * SEARCH_RANGE
                    && level.hasChunkAt(personal)
                    && WarehouseContainers.asContainer(level.getBlockEntity(personal)) != null) {
                privateStorage = personal;
            }
        }
        BlockPos target = storagePosition();
        if (target == null) return false;
        approach = isInInteractionRange(target)
                ? npc.blockPosition() : InteractionApproach.find(npc, target);
        if (approach == null) {
            warehouse = null;
            privateStorage = null;
            householdStorage = null;
            householdId = null;
            return false;
        }
        return true;
    }

    private boolean canUseWarehouse(ServerLevel level, WarehouseRecord record) {
        return NpcWorkContext.from(npc).canUseWarehouse(level.getServer(), record);
    }

    @Override
    public boolean canContinueToUse() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return false;
        return storagePosition() != null && npc.isAlive() && npc.getTarget() == null
                && npc.getCommand() == NpcCommand.WORK && outputCount() > 0
                && NpcSchedulePlanner.allowsWork(npc);
    }

    @Override
    public void start() {
        recalcPath = 0;
        pathFailures = 0;
        if (approach != null) movementProgress.reset(npc, approach);
        npc.reportWorkActivity(warehouse != null ? "生产物资入公共仓库"
                        : householdStorage != null ? "向家庭共享储物贡献部分产出" : "生产物资存入私人储物",
                storagePosition(), "待入库=" + outputCount() + "，交互站位="
                        + (approach == null ? "无" : approach.toShortString()));
    }

    @Override
    public void tick() {
        if (com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(npc)) return;
        if (!(npc.level() instanceof ServerLevel level)) return;
        BlockPos pos = storagePosition();
        if (pos == null) return;
        npc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        if (!InteractionApproach.readyToInteract(
                npc, pos, approach, INTERACTION_RANGE_SQ)) {
            if (approach == null) {
                warehouse = null;
                privateStorage = null;
                householdStorage = null;
                return;
            }
            if (movementProgress.tick(npc, approach)) {
                if (++pathFailures >= 3) {
                    npc.reportWorkActivity("生产物资入库", pos, "寻路连续失败，稍后重试");
                    warehouse = null;
                    privateStorage = null;
                    householdStorage = null;
                    householdId = null;
                    return;
                }
                approach = InteractionApproach.find(npc, pos);
                if (approach == null) {
                    warehouse = null;
                    privateStorage = null;
                    householdStorage = null;
                    householdId = null;
                    return;
                }
                movementProgress.reset(npc, approach);
                recalcPath = 0;
            }
            if (--recalcPath <= 0) {
                recalcPath = 20;
                if (!InteractionApproach.move(npc, approach, 1.0D)) pathFailures++;
            }
            return;
        }
        Container target = WarehouseContainers.asContainer(level.getBlockEntity(pos));
        int moved = target == null ? 0 : depositOutputs(level, npc.getEquipmentInventory(), target,
                householdStorage == null ? Integer.MAX_VALUE : Math.max(1, outputCount() / 4));
        npc.reportWorkActivity("生产物资入库", pos,
                target == null ? "仓库容器失效" : "本轮转移=" + moved + "，剩余=" + outputCount());
        // 无论是否全部放下，本轮都结束；仓库满时不允许 NPC 永久占用 MOVE。
        warehouse = null;
        privateStorage = null;
        householdStorage = null;
        householdId = null;
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        warehouse = null;
        privateStorage = null;
        householdStorage = null;
        householdId = null;
        approach = null;
    }

    private int outputCount() {
        ReserveBudget reserve = createReserveBudget();
        int count = 0;
        SimpleContainer bag = npc.getEquipmentInventory();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            ItemStack stack = bag.getItem(slot);
            if (isOutput(stack)) count += Math.max(0, stack.getCount() - reserve.keep(stack));
        }
        return count;
    }

    private int depositOutputs(ServerLevel level, SimpleContainer source, Container target, int transferLimit) {
        BlockPos targetPosition = storagePosition();
        if (targetPosition == null || transferLimit <= 0) return 0;

        RightsStateData rights = RightsStateData.get(level.getServer());
        SubjectRef producer = SubjectRef.character(npc.getCharacterId());
        UUID sourceId = ContainerIds.npcPersonal(npc.getCharacterId());
        rights.ensureContext(sourceId, "npc:" + npc.getCharacterId() + ":personal",
                producer, producer, AccessPolicy.PRIVATE);

        UUID targetId;
        SubjectRef targetOwner;
        AccessPolicy targetPolicy;
        TransferIntent intent;
        String authority;
        if (warehouse != null) {
            targetId = warehouse.id();
            targetOwner = SubjectRef.institution(warehouse.settlementId());
            targetPolicy = AccessPolicy.INSTITUTION;
            intent = TransferIntent.DELIVERY;
            authority = "work_output:" + warehouse.settlementId();
        } else if (householdStorage != null && householdId != null) {
            targetId = ContainerIds.block("household_storage",
                    level.dimension().identifier().toString(), targetPosition);
            targetOwner = SubjectRef.household(householdId);
            targetPolicy = AccessPolicy.HOUSEHOLD;
            intent = TransferIntent.GIFT;
            authority = "household_contribution:" + householdId;
        } else {
            targetId = ContainerIds.block("private_storage",
                    level.dimension().identifier().toString(), targetPosition);
            targetOwner = producer;
            targetPolicy = AccessPolicy.PRIVATE;
            intent = TransferIntent.DELIVERY;
            authority = "personal_storage";
        }
        rights.ensureContext(targetId, level.dimension().identifier() + ":" + targetPosition.asLong(),
                targetOwner, targetOwner, targetPolicy);

        ReserveBudget reserve = createReserveBudget();
        int remainingLimit = transferLimit;
        int movedTotal = 0;
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            if (remainingLimit <= 0) break;
            ItemStack stack = source.getItem(slot);
            if (!isOutput(stack)) continue;
            int transferable = Math.min(remainingLimit, stack.getCount() - reserve.keep(stack));
            if (transferable <= 0) continue;
            int quantity = Math.min(transferable, capacityFor(target, stack));
            if (quantity <= 0) continue;
            long sourceRevision = rights.context(sourceId).revision();
            long targetRevision = rights.context(targetId).revision();
            UUID transferId = ContainerIds.named("production_deposit:" + npc.getCharacterId()
                    + ':' + targetId + ':' + sourceRevision + ':' + targetRevision + ':' + slot);
            TransferRequest request = new TransferRequest(transferId, producer, intent,
                    List.of(new TransferLeg(sourceId, targetId, ItemFingerprint.of(stack), quantity)),
                    List.of(new ExpectedRevision(sourceId, sourceRevision),
                            new ExpectedRevision(targetId, targetRevision)),
                    authority, level.getGameTime());
            var receipt = new TransferService(rights, TransferLedgerData.get(level.getServer()))
                    .submit(request, Map.of(sourceId, new TransferEndpoint(sourceId, source),
                            targetId, new TransferEndpoint(targetId, target)), level.getGameTime());
            if (!receipt.successful()) {
                npc.reportWorkActivity("生产物资入库", targetPosition,
                        "统一转移未提交：" + receipt.reason());
                break;
            }
            movedTotal += quantity;
            remainingLimit -= quantity;
        }
        if (movedTotal > 0) npc.playAction(com.jgeted.sagadyssey.npc.entity.NpcAction.TRADE_PRESENT, 20);
        return movedTotal;
    }

    /**
     * 每次计数或入库都创建一份相同的保留预算，保证“是否出发”和“实际交多少”使用同一套规则。
     * 工人按材料组保留垫脚方块；农民按物品分别保留种苗。
     */
    private ReserveBudget createReserveBudget() {
        Map<Item, Integer> itemReserves = new HashMap<>();
        int scaffoldReserve = 0;
        if (npc.getProfession() == NpcProfession.WORKER) {
            scaffoldReserve = WORKER_SCAFFOLD_RESERVE;
        } else if (npc.getProfession() == NpcProfession.FARMER) {
            // 有固定农田时保留一整组播种物资，避免刚领取的种子尚未下田就被入库 Goal 搬走。
            int cropReserve = 8;
            if (npc.hasWorkZone()) {
                long width = (long) npc.getWorkZoneMax().getX() - npc.getWorkZoneMin().getX() + 1L;
                long depth = (long) npc.getWorkZoneMax().getZ() - npc.getWorkZoneMin().getZ() + 1L;
                cropReserve = (int) Math.max(16L, Math.min(128L, width * depth));
            }
            itemReserves.put(Items.WHEAT_SEEDS, cropReserve);
            itemReserves.put(Items.CARROT, cropReserve);
            itemReserves.put(Items.POTATO, cropReserve);
            itemReserves.put(Items.BEETROOT_SEEDS, cropReserve);
            itemReserves.put(Items.MELON_SEEDS, 4);
            itemReserves.put(Items.PUMPKIN_SEEDS, 4);
            itemReserves.put(Items.SUGAR_CANE, 8);
            itemReserves.put(Items.COCOA_BEANS, 8);
        } else if (npc.getProfession() == NpcProfession.BLACKSMITH) {
            itemReserves.put(Items.IRON_INGOT, 8);
        }
        return new ReserveBudget(itemReserves, scaffoldReserve);
    }

    private static boolean isWorkerScaffoldMaterial(ItemStack stack) {
        return stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)
                || stack.is(Items.STONE) || stack.is(ItemTags.LOGS);
    }

    private static final class ReserveBudget {
        private final Map<Item, Integer> itemRemaining;
        private int scaffoldRemaining;

        private ReserveBudget(Map<Item, Integer> itemRemaining, int scaffoldRemaining) {
            this.itemRemaining = itemRemaining;
            this.scaffoldRemaining = scaffoldRemaining;
        }

        private int keep(ItemStack stack) {
            if (scaffoldRemaining > 0 && isWorkerScaffoldMaterial(stack)) {
                int kept = Math.min(stack.getCount(), scaffoldRemaining);
                scaffoldRemaining -= kept;
                return kept;
            }
            int remaining = itemRemaining.getOrDefault(stack.getItem(), 0);
            if (remaining <= 0) return 0;
            int kept = Math.min(stack.getCount(), remaining);
            itemRemaining.put(stack.getItem(), remaining - kept);
            return kept;
        }
    }

    private boolean isOutput(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return switch (npc.getProfession()) {
            case FARMER -> stack.is(Items.WHEAT) || stack.is(Items.CARROT) || stack.is(Items.POTATO)
                    || stack.is(Items.BEETROOT) || stack.is(Items.BEETROOT_SEEDS)
                    || stack.is(Items.WHEAT_SEEDS) || stack.is(Items.MELON_SLICE)
                    || stack.is(Items.PUMPKIN) || stack.is(Items.SUGAR_CANE) || stack.is(Items.COCOA_BEANS);
            case WORKER -> stack.is(ItemTags.LOGS) || stack.is(Items.COBBLESTONE) || stack.is(Items.STONE)
                    || stack.is(Items.COAL) || stack.is(Items.CHARCOAL) || stack.is(Items.RAW_IRON)
                    || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD) || stack.is(Items.DIAMOND)
                    || stack.is(Items.REDSTONE) || stack.is(Items.LAPIS_LAZULI) || stack.is(Items.EMERALD);
            case BLACKSMITH -> stack.is(Items.IRON_SWORD) || stack.is(Items.IRON_AXE)
                    || stack.is(Items.IRON_PICKAXE) || stack.is(Items.IRON_SHOVEL)
                    || stack.is(Items.IRON_HOE) || stack.is(Items.SHIELD)
                    || stack.is(Items.IRON_INGOT);
            default -> false;
        };
    }

    private static int capacityFor(Container container, ItemStack sample) {
        int capacity = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                capacity += Math.min(container.getMaxStackSize(), sample.getMaxStackSize());
            } else if (ItemStack.isSameItemSameComponents(existing, sample)) {
                capacity += Math.max(0, Math.min(container.getMaxStackSize(), existing.getMaxStackSize())
                        - existing.getCount());
            }
        }
        return capacity;
    }

    private static boolean isProducer(NpcProfession profession) {
        return profession == NpcProfession.FARMER || profession == NpcProfession.WORKER
                || profession == NpcProfession.BLACKSMITH;
    }

    private boolean isInInteractionRange(BlockPos pos) {
        return npc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                <= INTERACTION_RANGE_SQ;
    }

    @Nullable
    private BlockPos storagePosition() {
        return warehouse != null ? warehouse.position()
                : householdStorage != null ? householdStorage : privateStorage;
    }
}
