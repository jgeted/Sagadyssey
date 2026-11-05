package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.worldsim.rights.AssetClaim;
import com.jgeted.sagadyssey.worldsim.rights.AssetRight;
import com.jgeted.sagadyssey.worldsim.rights.ContainerContext;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsService;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.RightsVerdict;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Atomic destruction of physical goods for eating, fuel, repairs, or other final use. */
public final class ConsumptionService {
    private final RightsStateData rights;
    private final RightsService resolver;

    public ConsumptionService(RightsStateData rights) {
        this.rights = rights;
        this.resolver = new RightsService(rights);
    }

    public Result consume(UUID containerId, Container container, SubjectRef actor,
                          ItemFingerprint fingerprint, int quantity,
                          String authorityToken, long now) {
        if (containerId == null || container == null || actor == null || fingerprint == null
                || quantity <= 0) return Result.failure(Status.INVALID, "消费请求不完整");
        ContainerContext context = rights.context(containerId);
        if (context == null) return Result.failure(Status.INVALID, "消费容器没有权利上下文");
        String token = authorityToken == null ? "" : authorityToken;
        int physical = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (fingerprint.matches(container.getItem(slot))) physical += container.getItem(slot).getCount();
        }
        if (physical - rights.reservedQuantity(containerId, fingerprint, token, now) < quantity) {
            return Result.failure(Status.RESERVED, "可用数量已经承诺给生产、交易或交付");
        }

        ItemStack[] snapshot = snapshot(container);
        RightsStateData.TransactionSnapshot rightsSnapshot = rights.snapshotFor(Set.of(containerId));
        List<Integer> changed = new ArrayList<>();
        int remaining = quantity;
        boolean denied = false;
        try {
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (!fingerprint.matches(stack)) continue;
                RightsVerdict verdict = resolver.can(actor, AssetRight.CONSUME,
                        containerId, slot, fingerprint, now);
                if (verdict.status() != RightsVerdict.Status.ALLOW) {
                    denied = true;
                    continue;
                }
                AssetClaim claim = rights.claimAt(containerId, slot, fingerprint);
                if (claim != null && claim.quantity() != stack.getCount()) {
                    throw new IllegalStateException("资产声明数量与实际堆叠不一致");
                }
                if (claim != null && !claim.reservations().isEmpty()
                        && !claim.reservations().contains(token)) {
                    denied = true;
                    continue;
                }
                SubjectRef owner = claim == null ? context.defaultBeneficialOwner()
                        : claim.beneficialOwner();
                int ownerReserved = rights.reservedQuantity(containerId, fingerprint,
                        owner, token, now);
                int ownerPhysical = physicalForOwner(containerId, container, fingerprint,
                        owner, context);
                int usable = Math.max(0, ownerPhysical - ownerReserved);
                if (usable <= 0) {
                    denied = true;
                    continue;
                }
                int used = Math.min(remaining, Math.min(stack.getCount(), usable));
                stack.shrink(used);
                remaining -= used;
                changed.add(slot);
            }
            if (remaining > 0) {
                restore(container, snapshot);
                return Result.failure(denied ? Status.DENIED : Status.INSUFFICIENT,
                        denied ? "其余同类物品不属于消费方或已被预留" : "物品数量不足");
            }
            for (int slot : changed) {
                ItemStack originalStack = snapshot[slot];
                AssetClaim original = rightsSnapshot.claims().values().stream()
                        .filter(value -> value.containerId().equals(containerId)
                                && value.slot() == slot
                                && value.fingerprint().matches(originalStack))
                        .findFirst().orElse(null);
                rights.removeClaimsForSlot(containerId, slot);
                ItemStack current = container.getItem(slot);
                if (original != null && !current.isEmpty()) {
                    rights.putClaim(new AssetClaim(original.claimId(), containerId, slot,
                            ItemFingerprint.of(current), current.getCount(), original.beneficialOwner(),
                            original.custodian(), original.encumbrances(), original.reservations(),
                            original.provenance(), original.revision() + 1L));
                }
            }
            rights.consumeReservation(token, containerId, fingerprint, quantity);
            long revision = rights.bumpContext(containerId);
            container.setChanged();
            return new Result(Status.SUCCESS, "", quantity, revision);
        } catch (RuntimeException exception) {
            restore(container, snapshot);
            rights.restore(rightsSnapshot);
            return Result.failure(Status.INTERNAL_FAILURE,
                    "消费失败并回滚：" + exception.getMessage());
        }
    }

    private int physicalForOwner(UUID containerId, Container container,
                                 ItemFingerprint fingerprint, SubjectRef owner,
                                 ContainerContext context) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!fingerprint.matches(stack)) continue;
            AssetClaim claim = rights.claimAt(containerId, slot, fingerprint);
            SubjectRef stackOwner = claim == null ? context.defaultBeneficialOwner()
                    : claim.beneficialOwner();
            if (owner.equals(stackOwner)) total += stack.getCount();
        }
        return total;
    }

    private static ItemStack[] snapshot(Container container) {
        ItemStack[] result = new ItemStack[container.getContainerSize()];
        for (int slot = 0; slot < result.length; slot++) result[slot] = container.getItem(slot).copy();
        return result;
    }

    private static void restore(Container container, ItemStack[] snapshot) {
        for (int slot = 0; slot < snapshot.length; slot++) container.setItem(slot, snapshot[slot].copy());
        container.setChanged();
    }

    public record Result(Status status, String reason, int consumed, long finalRevision) {
        public boolean successful() { return status == Status.SUCCESS; }
        static Result failure(Status status, String reason) {
            return new Result(status, reason, 0, -1L);
        }
    }

    public enum Status { SUCCESS, INVALID, INSUFFICIENT, RESERVED, DENIED, INTERNAL_FAILURE }
}
