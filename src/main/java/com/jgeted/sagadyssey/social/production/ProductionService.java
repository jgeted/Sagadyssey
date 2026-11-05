package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.worldsim.rights.AssetClaim;
import com.jgeted.sagadyssey.worldsim.rights.AssetReservation;
import com.jgeted.sagadyssey.worldsim.rights.AssetRight;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsService;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.RightsVerdict;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Atomic one-container crafting with durable inputs, labor gate and explicit output title. */
public final class ProductionService {
    private ProductionService() {}

    public static ProductionBatch begin(MinecraftServer server, UUID workerId, SubjectRef owner,
            UUID containerId, Container inventory, String recipeId,
            List<ProductionMaterial> inputs, ProductionMaterial output,
            int requiredWorkTicks, long now) {
        return begin(server, UUID.randomUUID(), workerId, owner, containerId, inventory,
                recipeId, inputs, output, requiredWorkTicks, now);
    }

    /**
     * 使用计划预先生成的批次 ID 开始生产；重复提交同一意图会返回原批次。
     */
    public static ProductionBatch begin(MinecraftServer server, UUID batchId,
            UUID workerId, SubjectRef owner, UUID containerId, Container inventory,
            String recipeId, List<ProductionMaterial> inputs, ProductionMaterial output,
            int requiredWorkTicks, long now) {
        if (batchId == null) throw new IllegalArgumentException("batchId is required");
        Set<ItemFingerprint> unique = new HashSet<>();
        if (inputs.stream().anyMatch(input -> !unique.add(input.fingerprint()))) {
            throw new IllegalArgumentException("相同原料必须合并为一行");
        }
        ProductionBatch batch = new ProductionBatch(batchId, recipeId, workerId, owner, containerId,
                inputs, output, requiredWorkTicks, now, 0L, ProductionBatchStatus.RESERVED,
                "原料已预留，等待劳动完成");
        ProductionBatchData batchData = ProductionBatchData.get(server);
        ProductionBatch existing = batchData.batch(batchId);
        if (existing != null) {
            if (!existing.sameIntentAs(batch)) {
                throw new IllegalStateException("生产批次 ID 已被其他意图使用");
            }
            if (existing.status() == ProductionBatchStatus.COMPLETED) return existing;
            if (existing.status() == ProductionBatchStatus.RESERVED) {
                ensureInputReservations(server, existing, inventory, now);
                return existing;
            }
            throw new IllegalStateException("生产批次已经以 " + existing.status() + " 结束");
        }
        RightsStateData rights = RightsStateData.get(server);
        try {
            ensureInputReservations(server, batch, inventory, now);
            batchData.put(batch);
            return batch;
        } catch (RuntimeException exception) {
            rights.releaseReservations(batch.token());
            throw exception;
        }
    }

    public static ProductionBatch complete(MinecraftServer server, UUID batchId,
            Container inventory, int workedTicks, long now) {
        var batch = ProductionBatchData.get(server).batch(batchId);
        if (batch != null && batch.status() == ProductionBatchStatus.COMPLETED) return batch;
        if (batch == null) throw new IllegalStateException("生产批次不存在");
        return complete(server, batchId, inventory, workedTicks, now, outputStack(batch.output()));
    }

    /** Component-bearing output is fixed by the batch's exact fingerprint, not supplied after settlement. */
    public static ProductionBatch complete(MinecraftServer server, UUID batchId,
            Container inventory, int workedTicks, long now, ItemStack producedTemplate) {
        ProductionBatchData data = ProductionBatchData.get(server);
        ProductionBatch batch = data.batch(batchId);
        if (batch != null && batch.status() == ProductionBatchStatus.COMPLETED) return batch;
        if (batch == null || batch.status() != ProductionBatchStatus.RESERVED) {
            throw new IllegalStateException("生产批次不存在或已经结束");
        }
        if (workedTicks < batch.requiredWorkTicks()) throw new IllegalStateException("劳动进度尚未完成");
        RightsStateData rights = RightsStateData.get(server);
        var context = rights.context(batch.containerId());
        if (context == null) throw new IllegalStateException("生产容器权利上下文丢失");
        if (producedTemplate == null || producedTemplate.isEmpty()
                || !batch.output().fingerprint().matches(producedTemplate)
                || producedTemplate.getCount() != batch.output().quantity()
                || producedTemplate.getCount() > producedTemplate.getMaxStackSize()) {
            throw new IllegalStateException("成品组件或数量不符合固定生产意图");
        }
        ItemStack produced = producedTemplate.copy();
        int outputSlot = findOutputSlot(inventory, batch.containerId(), produced,
                batch.outputOwner(), rights, now);
        if (outputSlot < 0) {
            throw new ProductionBlockedException("OUTPUT_FULL", "没有空间存放生产成品");
        }
        ItemStack[] physicalSnapshot = snapshot(inventory);
        var rightsSnapshot = rights.snapshotFor(Set.of(batch.containerId()));
        try {
            List<AssetReservation> activeReservations = rights.reservationsForToken(batch.token(), now);
            for (ProductionMaterial input : batch.inputs()) {
                int reserved = activeReservations.stream().filter(value ->
                        value.containerId().equals(batch.containerId())
                                && value.fingerprint().equals(input.fingerprint()))
                        .mapToInt(AssetReservation::quantity).sum();
                if (reserved < input.quantity()) {
                    throw new IllegalStateException("生产预留已经过期或不完整");
                }
            }
            RightsService resolver = new RightsService(rights);
            Set<Integer> changed = new HashSet<>();
            for (ProductionMaterial input : batch.inputs()) {
                int remaining = input.quantity();
                for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (!input.fingerprint().matches(stack)) continue;
                    RightsVerdict verdict = resolver.can(batch.outputOwner(), AssetRight.TRANSFER,
                            batch.containerId(), slot, input.fingerprint(), now);
                    if (verdict.status() != RightsVerdict.Status.ALLOW) continue;
                    int used = Math.min(remaining, stack.getCount());
                    stack.shrink(used);
                    remaining -= used;
                    changed.add(slot);
                }
                if (remaining > 0) throw new IllegalStateException("预留原料在完工前发生变化");
            }
            ItemStack outputStack = inventory.getItem(outputSlot);
            if (outputStack.isEmpty()) inventory.setItem(outputSlot, produced);
            else outputStack.grow(produced.getCount());
            changed.add(outputSlot);

            for (int slot : changed) {
                AssetClaim original = claimForSnapshot(rightsSnapshot, batch.containerId(), slot,
                        physicalSnapshot[slot]);
                rights.removeClaimsForSlot(batch.containerId(), slot);
                ItemStack current = inventory.getItem(slot);
                if (current.isEmpty()) continue;
                if (slot == outputSlot) {
                    if (!batch.outputOwner().equals(context.defaultBeneficialOwner())) {
                        rights.putClaim(new AssetClaim(claimId(batch.id(), slot), batch.containerId(), slot,
                                ItemFingerprint.of(current), current.getCount(), batch.outputOwner(),
                                context.custodian(), Set.of(), Set.of(), "production:" + batch.id(), 0L));
                    }
                } else if (original != null) {
                    rights.putClaim(new AssetClaim(original.claimId(), original.containerId(), slot,
                            ItemFingerprint.of(current), current.getCount(), original.beneficialOwner(),
                            original.custodian(), original.encumbrances(), original.reservations(),
                            original.provenance(), original.revision() + 1L));
                }
            }
            batch.inputs().forEach(input -> rights.consumeReservation(batch.token(),
                    batch.containerId(), input.fingerprint(), input.quantity()));
            rights.bumpContext(batch.containerId());
            inventory.setChanged();
            ProductionBatch completed = batch.finish(ProductionBatchStatus.COMPLETED, now,
                    "劳动完成；原料已消耗，成品所有权已登记");
            data.put(completed);
            return completed;
        } catch (RuntimeException exception) {
            restore(inventory, physicalSnapshot);
            rights.restore(rightsSnapshot);
            // A failed batch is terminal. Restoring the transaction snapshot must not
            // resurrect its promise and leave otherwise usable goods locked forever.
            rights.releaseReservations(batch.token());
            ProductionBatch failed = batch.finish(ProductionBatchStatus.FAILED, now,
                    "生产失败并回滚：" + exception.getMessage());
            data.put(failed);
            throw exception;
        }
    }

    public static void cancel(MinecraftServer server, UUID batchId, long now, String reason) {
        cancel(ProductionBatchData.get(server), RightsStateData.get(server), batchId, now, reason);
    }

    static void cancel(ProductionBatchData data, RightsStateData rights,
                       UUID batchId, long now, String reason) {
        ProductionBatch batch = data.batch(batchId);
        // 批次已结束或已被淘汰时，也不能留下同一令牌的原料承诺。
        rights.releaseReservations("production:" + batchId);
        if (batch == null || batch.status().terminal()) return;
        data.put(batch.finish(ProductionBatchStatus.CANCELLED, now, reason));
    }

    /** 返回当前不能由订单所有者处分足量的完整输入行，供幂等缺料请求使用。 */
    public static List<ProductionMaterial> missingInputs(MinecraftServer server,
                                                          UUID containerId,
                                                          Container inventory,
                                                          SubjectRef owner,
                                                          List<ProductionMaterial> inputs,
                                                          long now) {
        if (RightsStateData.get(server).context(containerId) == null) {
            throw new IllegalStateException("生产容器没有权利上下文");
        }
        RightsService resolver = new RightsService(RightsStateData.get(server));
        return inputs.stream().filter(input -> transferableCount(inventory, containerId,
                input.fingerprint(), owner, resolver, now) < input.quantity()).toList();
    }

    public static boolean canAcceptOutput(MinecraftServer server, UUID containerId,
                                          Container inventory, SubjectRef owner,
                                          ProductionMaterial output, long now) {
        RightsStateData rights = RightsStateData.get(server);
        if (rights.context(containerId) == null) return false;
        ItemStack produced = outputStack(output);
        return findOutputSlot(inventory, containerId, produced, owner, rights, now) >= 0;
    }

    private static int transferableCount(Container inventory, UUID containerId,
            ItemFingerprint fingerprint, SubjectRef owner, RightsService resolver, long now) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (fingerprint.matches(stack) && resolver.can(owner, AssetRight.TRANSFER,
                    containerId, slot, fingerprint, now).status() == RightsVerdict.Status.ALLOW) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 重载或长时间阻塞后重新核验并恢复输入承诺；已有完整承诺时保持幂等。
     */
    private static void ensureInputReservations(MinecraftServer server, ProductionBatch batch,
                                                Container inventory, long now) {
        RightsStateData rights = RightsStateData.get(server);
        if (rights.context(batch.containerId()) == null) {
            throw new ProductionBlockedException("RIGHTS_CONTEXT_MISSING",
                    "生产容器没有权利上下文");
        }
        List<AssetReservation> active = rights.reservationsForToken(batch.token(), now);
        boolean complete = batch.inputs().stream().allMatch(input -> active.stream()
                .filter(value -> value.containerId().equals(batch.containerId())
                        && value.fingerprint().equals(input.fingerprint()))
                .mapToInt(AssetReservation::quantity).sum() >= input.quantity());
        long reservationExpiresAt = reservationExpiry(now, batch.requiredWorkTicks());
        if (complete) {
            rights.renewReservations(batch.token(), now, reservationExpiresAt);
            return;
        }

        rights.releaseReservations(batch.token());
        RightsService resolver = new RightsService(rights);
        try {
            for (ProductionMaterial input : batch.inputs()) {
                int owned = transferableCount(inventory, batch.containerId(), input.fingerprint(),
                        batch.outputOwner(), resolver, now);
                AssetReservation reservation = new AssetReservation(UUID.randomUUID(),
                        batch.containerId(), input.fingerprint(), input.quantity(),
                        batch.outputOwner(), batch.token(), batch.recipeId(), now,
                        reservationExpiresAt);
                if (!rights.reserve(reservation, owned, now)) {
                    throw new ProductionBlockedException("MISSING_INPUT",
                            "原料不足，或已经承诺给其他生产、交易或交付");
                }
            }
        } catch (RuntimeException exception) {
            rights.releaseReservations(batch.token());
            throw exception;
        }
    }

    private static long reservationExpiry(long now, int requiredWorkTicks) {
        long duration = Math.max(12_000L, (long) requiredWorkTicks + 2_400L);
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    private static ItemStack outputStack(ProductionMaterial output) {
        ResourceLocation id = ResourceLocation.tryParse(output.fingerprint().itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalStateException("未知成品物品");
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        ItemStack stack = new ItemStack(item, output.quantity());
        if (output.quantity() > stack.getMaxStackSize()) {
            throw new IllegalStateException("单批成品超过堆叠上限");
        }
        if (ItemFingerprint.of(stack).componentsHash() != output.fingerprint().componentsHash()) {
            throw new IllegalStateException("该成品需要尚未登记的组件数据");
        }
        return stack;
    }

    private static int findOutputSlot(Container inventory, UUID containerId,
                                      ItemStack produced, SubjectRef owner,
                                      RightsStateData rights, long now) {
        ItemFingerprint fingerprint = ItemFingerprint.of(produced);
        var context = rights.context(containerId);
        if (context == null) return -1;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(existing, produced)
                    && existing.getCount() + produced.getCount() <= existing.getMaxStackSize()
                    && isWhollyOwnedBy(rights, containerId, slot, fingerprint,
                    context.defaultBeneficialOwner(), owner)) {
                return slot;
            }
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /**
     * 只有整叠旧物品与新产出属于同一受益所有者时才允许叠堆。
     * “允许转移”不等于“拥有所有权”，否则结算重写 claim 时会吞并他人物品。
     */
    static boolean isWhollyOwnedBy(RightsStateData rights, UUID containerId, int slot,
                                    ItemFingerprint fingerprint, SubjectRef defaultOwner,
                                    SubjectRef expectedOwner) {
        List<AssetClaim> claims = rights.claimsAt(containerId, slot, fingerprint);
        if (claims.isEmpty()) return defaultOwner.equals(expectedOwner);
        return claims.size() == 1 && claims.getFirst().beneficialOwner().equals(expectedOwner);
    }

    private static ItemStack[] snapshot(Container inventory) {
        ItemStack[] result = new ItemStack[inventory.getContainerSize()];
        for (int slot = 0; slot < result.length; slot++) result[slot] = inventory.getItem(slot).copy();
        return result;
    }

    private static void restore(Container inventory, ItemStack[] snapshot) {
        for (int slot = 0; slot < snapshot.length; slot++) inventory.setItem(slot, snapshot[slot].copy());
        inventory.setChanged();
    }

    private static AssetClaim claimForSnapshot(RightsStateData.TransactionSnapshot snapshot,
            UUID containerId, int slot, ItemStack stack) {
        if (stack.isEmpty()) return null;
        ItemFingerprint fingerprint = ItemFingerprint.of(stack);
        return snapshot.claims().values().stream().filter(claim ->
                claim.containerId().equals(containerId) && claim.slot() == slot
                        && claim.fingerprint().equals(fingerprint)).findFirst().orElse(null);
    }

    private static UUID claimId(UUID batchId, int slot) {
        return UUID.nameUUIDFromBytes((batchId + ":output:" + slot)
                .getBytes(StandardCharsets.UTF_8));
    }
}
