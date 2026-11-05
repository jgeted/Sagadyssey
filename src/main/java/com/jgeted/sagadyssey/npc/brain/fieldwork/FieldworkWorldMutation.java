package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.npc.brain.execution.NpcEntityExecutionContext;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.inventory.NpcPersonalInventory;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** 真实服务端单方块事务。预演阶段无库存/账本写入，失败恢复本 NPC 的局部快照。 */
public final class FieldworkWorldMutation implements FieldworkCommitService.Mutation {
    private final NpcBase npc;
    private final ServerLevel level;
    private final FieldworkAction action;
    private final BlockState before, after;
    private final RightsStateData rights;
    private final UUID containerId;
    private final SubjectRef actor;
    private final RightsStateData.TransactionSnapshot rightsBefore;
    private final ItemStack[] original, working;
    private final long inventoryRevision;
    private final long now;
    private final FieldworkActionReceipt receipt;
    private FieldworkCommitService.Blocker blocker;

    public FieldworkWorldMutation(NpcEntityExecutionContext context, FieldworkAction action,
                                  Map<String, String> params, long now) {
        npc = context.npc(); level = context.level(); this.action = action; this.now = now;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("fieldwork must run on server thread");
        try {
            before = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), TagParser.parseTag(action.expectedState()));
            after = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), TagParser.parseTag(params.get("result")));
        } catch (Exception exception) { throw new IllegalArgumentException("invalid block intent", exception); }
        rights = RightsStateData.get(level.getServer());
        containerId = ContainerIds.npcPersonal(npc.getCharacterId());
        actor = SubjectRef.character(npc.getCharacterId());
        rightsBefore = rights.snapshotFor(Set.of(containerId));
        NpcPersonalInventory inventory = npc.getPersonalInventory();
        inventoryRevision = inventory.revision();
        original = new ItemStack[inventory.getContainerSize() + 1];
        working = new ItemStack[original.length];
        for (int slot = 0; slot < original.length; slot++) {
            original[slot] = slot == original.length - 1 ? npc.getMainHandItem().copy() : inventory.getItem(slot).copy();
            working[slot] = original[slot].copy();
        }
        List<FieldworkActionReceipt.ItemDelta> deltas = new ArrayList<>();
        int wear = 0;
        boolean breaking = action.operation() == FieldworkAction.Operation.MINE
                || action.operation() == FieldworkAction.Operation.CHOP || action.operation() == FieldworkAction.Operation.HARVEST;
        int toolSlot = toolSlot(action.operation(), before);
        if (needsTool(action.operation()) && toolSlot < 0) block("MISSING_TOOL", "缺少适用工具或使用权");
        if (before.hasBlockEntity() || after.hasBlockEntity()) block("BLOCK_ENTITY_FORBIDDEN", "不能把储物或功能方块当作普通材料作业");
        if (params.containsKey("input")) {
            Item input = BuiltInRegistries.ITEM.get(ResourceLocation.parse(params.get("input")));
            int slot = consumeSlot(input);
            if (slot < 0) block("MISSING_INPUT", "没有可消耗的投入物品");
            else { deltas.add(delta(working[slot], -1)); working[slot].shrink(1); }
        }
        List<ItemStack> outputs = new ArrayList<>();
        if (breaking || action.operation() == FieldworkAction.Operation.IRRIGATE) {
            ItemStack tool = toolSlot < 0 ? ItemStack.EMPTY : original[toolSlot];
            if (!before.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(before)) {
                outputs.addAll(Block.getDrops(before, level, action.target(), null, npc, tool));
            }
        }
        if (action.operation() == FieldworkAction.Operation.RECOVER_SCAFFOLD) outputs.add(new ItemStack(before.getBlock()));
        if (params.containsKey("replacement")) {
            int quantity = Integer.parseInt(params.getOrDefault("replacement_count", "1"));
            if (quantity < 1 || quantity > 64) throw new IllegalArgumentException("invalid replacement quantity");
            outputs.add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(params.get("replacement"))), quantity));
        }
        for (ItemStack output : outputs) {
            if (output.isEmpty()) continue;
            if (!insert(output)) block("OUTPUT_FULL", "背包无法容纳精确产出");
            else deltas.add(delta(output, output.getCount()));
        }
        if (toolSlot >= 0 && needsTool(action.operation()) && !working[toolSlot].isEmpty()) {
            ItemStack tool = working[toolSlot];
            int previousDamage = tool.getDamageValue();
            ItemStack sample = tool.copy();
            // 在副本上使用游戏的附魔/损耗规则，不播放提前发生的断裂动作。
            tool.hurtAndBreak(1, level, (net.minecraft.world.entity.LivingEntity) null, ignored -> { });
            wear = tool.isEmpty() ? Math.max(0, sample.getMaxDamage() - previousDamage)
                    : Math.max(0, tool.getDamageValue() - previousDamage);
            if (tool.isEmpty()) deltas.add(delta(sample, -1));
        }
        receipt = new FieldworkActionReceipt(action, now, NbtUtils.writeBlockState(after).toString(), deltas, wear);
    }

    @Override public Optional<FieldworkCommitService.Blocker> validate() {
        if (blocker != null) return Optional.of(blocker);
        if (!npc.isAlive() || !action.npcId().equals(npc.getUUID())) return denied("NPC_INVALID", "作业实体失效");
        if (!action.dimension().equals(level.dimension().location().toString())) return denied("WRONG_DIMENSION", "目标维度已变化");
        if (!level.hasChunkAt(action.target())) return denied("CHUNK_UNLOADED", "目标区块未加载");
        boolean recovery = action.operation() == FieldworkAction.Operation.RECOVER_SCAFFOLD;
        if (action.operation() == FieldworkAction.Operation.PLACE_SCAFFOLD
                && !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(action.target())).isEmpty()) {
            return denied("PLACEMENT_OCCUPIED", "放置格仍有活体，不能把承重材料放进身体");
        }
        if (recovery && FieldworkData.get(level.getServer()).outstandingScaffolds(npc.getUUID()).stream()
                .noneMatch(value -> value.action().target().equals(action.target()) && value.action().dimension().equals(action.dimension())
                        && value.resultingState().equals(action.expectedState()))) return denied("NO_SCAFFOLD_RECEIPT", "不能回收没有本劳动者放置回执的方块");
        if (!recovery && !FieldworkDiscovery.allowed(npc, action.target())) return denied("OUTSIDE_WORK_AREA", "工作范围已变化");
        if (recovery && !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(action.target()).move(0, 1, 0)).isEmpty()) {
            return denied("SCAFFOLD_OCCUPIED", "承重方块上仍有活体，不能拆除");
        }
        if (!level.getBlockState(action.target()).equals(before)) return denied("TARGET_CHANGED", "目标已由他人或自然生长改变");
        if (!NpcBrainRuntime.reservations(level.getServer()).holds(key(action), action.planId(), action.npcId(), now)) {
            return denied("TARGET_RESERVED", "本计划未持有目标方块");
        }
        if (npc.getPersonalInventory().revision() != inventoryRevision) return denied("INVENTORY_CHANGED", "库存已变化");
        for (int slot = 0; slot < original.length; slot++) {
            ItemStack current = slot == original.length - 1 ? npc.getMainHandItem() : npc.getPersonalInventory().getItem(slot);
            if (!ItemStack.matches(original[slot], current)) return denied("INVENTORY_CHANGED", "投入或工具已经变化");
            if (!original[slot].isEmpty()) {
                var claims = rights.claimsAt(containerId, slot, ItemFingerprint.of(original[slot]));
                int physicalCount = original[slot].getCount();
                if (claims.size() > 1 || claims.stream().anyMatch(value -> value.quantity() != physicalCount)) {
                    return denied("RIGHTS_QUANTITY_MISMATCH", "物权主张与实际堆叠不一致");
                }
            }
        }
        if (!after.isAir() && action.operation() != FieldworkAction.Operation.REFILL_WATER
                && !after.canSurvive(level, action.target())) return denied("PLACEMENT_INVALID", "种植或放置条件已变化");
        return Optional.empty();
    }

    @Override public FieldworkActionReceipt preview() { return receipt; }
    @Override public boolean applySingleBlock() {
        return before.equals(after) || level.setBlock(action.target(), after, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
    @Override public void applyInventoryAndRights() {
        rights.ensureContext(containerId, "npc:" + npc.getCharacterId() + ":personal", actor, actor, AccessPolicy.PRIVATE);
        for (int slot = 0; slot < original.length; slot++) {
            if (ItemStack.matches(original[slot], working[slot])) continue;
            var prior = original[slot].isEmpty() ? null : rights.claimAt(containerId, slot, ItemFingerprint.of(original[slot]));
            SubjectRef owner = prior == null ? rights.context(containerId).defaultBeneficialOwner() : prior.beneficialOwner();
            // 新产出是劳动者所有；只有未变物品类型的投入/工具延续既有权利。
            if (original[slot].isEmpty()) owner = actor;
            rights.removeClaimsForSlot(containerId, slot);
            ItemStack updated = working[slot];
            if (!updated.isEmpty()) {
                rights.putClaim(new AssetClaim(ContainerIds.named("fieldwork:" + action.key() + ':' + slot),
                        containerId, slot, ItemFingerprint.of(updated), updated.getCount(), owner, actor,
                        prior == null ? Set.of() : prior.encumbrances(), prior == null ? Set.of() : prior.reservations(),
                        prior == null ? "fieldwork:" + action.key() : prior.provenance(),
                        rights.context(containerId).revision() + 1));
            }
            if (slot == original.length - 1) npc.setItemSlot(EquipmentSlot.MAINHAND, updated.copy());
            else npc.getPersonalInventory().setItem(slot, updated.copy());
        }
        rights.bumpContext(containerId);
    }

    /** 邻居更新与表现放在回执成功之后，不能在回滚前让上层甘蔗自然掉落。 */
    public void notifyCommitted() {
        if (!before.equals(after)) level.updateNeighborsAt(action.target(), after.getBlock());
    }

    @Override public void rollback() {
        if (!level.setBlock(action.target(), before, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)
                && !level.getBlockState(action.target()).equals(before)) throw new IllegalStateException("fieldwork block rollback failed");
        for (int slot = 0; slot < original.length; slot++) {
            if (slot == original.length - 1) npc.setItemSlot(EquipmentSlot.MAINHAND, original[slot].copy());
            else npc.getPersonalInventory().setItem(slot, original[slot].copy());
        }
        npc.getPersonalInventory().restoreRevision(inventoryRevision);
        rights.restore(rightsBefore);
    }

    private int consumeSlot(Item item) {
        for (int slot = 0; slot < working.length - 1; slot++) {
            if (working[slot].is(item) && permitted(slot, AssetRight.CONSUME)) return slot;
        }
        return -1;
    }
    private int toolSlot(FieldworkAction.Operation operation, BlockState state) {
        if (!needsTool(operation)) return -1;
        for (int slot = working.length - 1; slot >= 0; slot--) {
            ItemStack tool = working[slot];
            boolean suitable = operation == FieldworkAction.Operation.TILL ? tool.getItem() instanceof HoeItem
                    : operation == FieldworkAction.Operation.CHOP ? tool.getItem() instanceof AxeItem || tool.getItem() instanceof ShearsItem
                    : operation == FieldworkAction.Operation.MINE ? tool.getItem() instanceof PickaxeItem
                    || !FieldworkDiscovery.mineTarget(state) && tool.getItem() instanceof ShovelItem : true;
            if (!tool.isEmpty() && suitable && (!state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state))
                    && permitted(slot, AssetRight.USE)) return slot;
        }
        return -1;
    }
    private boolean permitted(int slot, AssetRight right) {
        ItemStack stack = original[slot];
        if (stack.isEmpty()) return false;
        if (rights.context(containerId) == null) return true;
        ItemFingerprint fingerprint = ItemFingerprint.of(stack);
        var claim = rights.claimAt(containerId, slot, fingerprint);
        if (claim != null && !claim.reservations().isEmpty() && !claim.reservations().contains("fieldwork:" + action.planId())) return false;
        if (rights.reservedQuantity(containerId, fingerprint, "fieldwork:" + action.planId(), now) > 0) return false;
        return new RightsService(rights).can(actor, right, containerId, slot, fingerprint, now).status() == RightsVerdict.Status.ALLOW;
    }
    private boolean insert(ItemStack offered) {
        ItemStack remaining = offered.copy();
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < working.length - 1 && !remaining.isEmpty(); slot++) {
                ItemStack stack = working[slot];
                if (pass == 0 && (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, remaining))) continue;
                if (pass == 1 && !stack.isEmpty()) continue;
                if (!original[slot].isEmpty()) {
                    var claim = rights.claimAt(containerId, slot, ItemFingerprint.of(original[slot]));
                    SubjectRef owner = claim == null ? rights.context(containerId) == null ? actor
                            : rights.context(containerId).defaultBeneficialOwner() : claim.beneficialOwner();
                    if (!owner.equals(actor) || claim != null && (!claim.encumbrances().isEmpty() || !claim.reservations().isEmpty())) continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize() - stack.getCount());
                if (moved <= 0) continue;
                if (stack.isEmpty()) working[slot] = remaining.copyWithCount(moved); else stack.grow(moved);
                remaining.shrink(moved);
            }
        }
        return remaining.isEmpty();
    }
    public static boolean needsTool(FieldworkAction.Operation operation) {
        return operation == FieldworkAction.Operation.MINE || operation == FieldworkAction.Operation.CHOP
                || operation == FieldworkAction.Operation.TILL;
    }
    public static String fingerprint(ItemStack stack) {
        ItemFingerprint fingerprint = ItemFingerprint.of(stack);
        return fingerprint.itemId() + '#' + fingerprint.componentsHash();
    }
    private static FieldworkActionReceipt.ItemDelta delta(ItemStack stack, int amount) {
        return new FieldworkActionReceipt.ItemDelta(fingerprint(stack), amount);
    }
    public static NpcReservationKey key(FieldworkAction action) {
        return new NpcReservationKey("fieldwork_block", action.dimension() + ':' + action.target().asLong());
    }
    private void block(String code, String detail) { if (blocker == null) blocker = new FieldworkCommitService.Blocker(code, detail); }
    private static Optional<FieldworkCommitService.Blocker> denied(String code, String detail) {
        return Optional.of(new FieldworkCommitService.Blocker(code, detail));
    }
}
