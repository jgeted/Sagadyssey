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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side two-phase transfer. All inventory mutations happen only after references, revisions,
 * rights, quantities, legal metadata, and destination capacity have passed together.
 */
public final class TransferService {
    private final RightsStateData rights;
    private final TransferLedgerData ledger;
    private final RightsService rightsService;

    public TransferService(RightsStateData rights, TransferLedgerData ledger) {
        this.rights = rights;
        this.ledger = ledger;
        this.rightsService = new RightsService(rights);
    }

    public TransferReceipt submit(TransferRequest request, Map<UUID, TransferEndpoint> endpoints,
                                  long completedAt) {
        // Rights state belongs to this world and is shared by its service instances.
        // Runtime transfers already run on the server thread. This monitor also keeps
        // concurrent submissions atomic without retaining a global lock per container.
        synchronized (rights) {
            TransferReceipt replay = ledger.receipt(request.transferId());
            if (replay != null) {
                if (ledger.conflictsWith(request))
                    return new TransferReceipt(request.transferId(), TransferReceipt.Status.INVALID,
                            false, "transfer id reused with a different request", Map.of(), completedAt);
                return replay;
            }
            Set<UUID> involved = new HashSet<>();
            request.legs().forEach(leg -> {
                involved.add(leg.sourceContainerId());
                involved.add(leg.destinationContainerId());
            });
            return executeLocked(request, endpoints, involved, completedAt);
        }
    }

    private TransferReceipt executeLocked(TransferRequest request,
                                          Map<UUID, TransferEndpoint> endpoints,
                                          Set<UUID> involved, long completedAt) {
        Map<UUID, Long> expected = new HashMap<>();
        for (ExpectedRevision revision : request.expectedRevisions()) {
            if (expected.put(revision.containerId(), revision.revision()) != null) {
                return fail(request, TransferReceipt.Status.INVALID, "重复的容器修订号", completedAt);
            }
        }
        for (UUID id : involved) {
            if (!endpoints.containsKey(id)) {
                return fail(request, TransferReceipt.Status.ENDPOINT_MISSING, "容器当前不可用: " + id, completedAt);
            }
            ContainerContext context = rights.context(id);
            if (context == null) {
                return fail(request, TransferReceipt.Status.ENDPOINT_MISSING, "容器尚未登记权利上下文: " + id,
                        completedAt);
            }
            Long requestedRevision = expected.get(id);
            if (requestedRevision == null || requestedRevision != context.revision()) {
                return fail(request, TransferReceipt.Status.STALE_REVISION, "库存已经变化，请重试", completedAt);
            }
        }

        Map<UUID, ItemStack[]> original = new LinkedHashMap<>();
        Map<UUID, ItemStack[]> working = new LinkedHashMap<>();
        Map<SlotRef, ClaimMeta> metadata = new HashMap<>();
        Set<SlotRef> changedSlots = new HashSet<>();
        for (UUID id : involved) {
            Container container = endpoints.get(id).container();
            ItemStack[] snapshot = new ItemStack[container.getContainerSize()];
            ItemStack[] copy = new ItemStack[container.getContainerSize()];
            ContainerContext context = rights.context(id);
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                snapshot[slot] = container.getItem(slot).copy();
                copy[slot] = snapshot[slot].copy();
                if (!copy[slot].isEmpty()) {
                    ItemFingerprint fingerprint = ItemFingerprint.of(copy[slot]);
                    AssetClaim claim = rights.claimAt(id, slot, fingerprint);
                    if (claim != null && claim.quantity() != copy[slot].getCount()) {
                        return fail(request, TransferReceipt.Status.RIGHTS_CONTESTED,
                                "资产声明数量与实际堆叠不一致", completedAt);
                    }
                    metadata.put(new SlotRef(id, slot), claim == null
                            ? ClaimMeta.defaults(context) : ClaimMeta.from(claim));
                }
            }
            original.put(id, snapshot);
            working.put(id, copy);
        }

        List<PendingInsertion> pendingInsertions = new ArrayList<>();
        for (TransferLeg leg : request.legs()) {
            ItemStack[] source = working.get(leg.sourceContainerId());
            ContainerContext destinationContext = rights.context(leg.destinationContainerId());
            int physicalMatching = 0;
            for (ItemStack stack : source) {
                if (leg.fingerprint().matches(stack)) physicalMatching += stack.getCount();
            }
            int reservedElsewhere = rights.reservedQuantity(leg.sourceContainerId(),
                    leg.fingerprint(), request.authority(), request.requestedAt());
            if (physicalMatching - reservedElsewhere < leg.quantity()) {
                return fail(request, TransferReceipt.Status.RIGHTS_DENIED,
                        "可用数量已被其他生产、交易或交付承诺预留", completedAt);
            }
            int remaining = leg.quantity();
            boolean sawDeniedBatch = false;
            boolean sawContestedBatch = false;
            for (int slot = 0; slot < source.length && remaining > 0; slot++) {
                ItemStack stack = source[slot];
                if (!leg.fingerprint().matches(stack)) continue;
                if (request.intent() != TransferIntent.THEFT) {
                    AssetRight required = request.intent() == TransferIntent.RETURN
                            ? AssetRight.POSSESS : AssetRight.TRANSFER;
                    SubjectRef sourceActor = request.sourceAuthorities().getOrDefault(
                            leg.sourceContainerId(), request.initiatedBy());
                    RightsVerdict verdict = rightsService.can(sourceActor, required,
                            leg.sourceContainerId(), slot, leg.fingerprint(), request.requestedAt());
                    if (verdict.status() == RightsVerdict.Status.CONTESTED) {
                        sawContestedBatch = true;
                        continue;
                    }
                    if (verdict.status() != RightsVerdict.Status.ALLOW) {
                        sawDeniedBatch = true;
                        continue;
                    }
                }

                int moved = Math.min(remaining, stack.getCount());
                SlotRef sourceRef = new SlotRef(leg.sourceContainerId(), slot);
                ClaimMeta sourceMeta = metadata.get(sourceRef);
                if (!sourceMeta.reservations().isEmpty()
                        && !sourceMeta.reservations().contains(request.authority())) {
                    sawDeniedBatch = true;
                    continue;
                }
                int ownerReservedElsewhere = rights.reservedQuantity(leg.sourceContainerId(),
                        leg.fingerprint(), sourceMeta.owner(), request.authority(), request.requestedAt());
                int ownerPhysical = physicalQuantityForOwner(source, leg.sourceContainerId(),
                        leg.fingerprint(), sourceMeta.owner(), metadata);
                int ownerAvailable = Math.max(0, ownerPhysical - ownerReservedElsewhere);
                if (ownerAvailable <= 0) {
                    sawDeniedBatch = true;
                    continue;
                }
                ClaimMeta targetMeta = targetMetadata(request, sourceMeta, destinationContext);
                moved = Math.min(moved, ownerAvailable);
                ItemStack moving = stack.copyWithCount(moved);
                stack.shrink(moved);
                if (stack.isEmpty()) metadata.remove(sourceRef);
                changedSlots.add(sourceRef);
                pendingInsertions.add(new PendingInsertion(leg.destinationContainerId(),
                        moving, targetMeta));
                remaining -= moved;
            }
            if (remaining > 0) {
                if (sawContestedBatch) {
                    return fail(request, TransferReceipt.Status.RIGHTS_CONTESTED,
                            "符合物品中存在权属争议批次", completedAt);
                }
                if (sawDeniedBatch) {
                    return fail(request, TransferReceipt.Status.RIGHTS_DENIED,
                            "符合物品数量不足，其余批次无处分权或已被预留", completedAt);
                }
                return fail(request, TransferReceipt.Status.INSUFFICIENT_ITEMS,
                        "来源物品数量不足", completedAt);
            }
        }

        // Remove every promised asset before inserting either side. Besides being closer to a
        // real simultaneous exchange, this lets two completely full inventories swap items by
        // using the slots vacated by their own outgoing goods.
        for (PendingInsertion pending : pendingInsertions) {
            if (!insert(working.get(pending.destinationContainerId()),
                    pending.destinationContainerId(), pending.stack(), pending.metadata(),
                    metadata, changedSlots)) {
                return fail(request, TransferReceipt.Status.CAPACITY_EXCEEDED,
                        "目标容器空间不足或权利批次不兼容", completedAt);
            }
        }

        RightsStateData.TransactionSnapshot rightsSnapshot = rights.snapshotFor(involved);
        try {
            for (UUID id : involved) {
                Container container = endpoints.get(id).container();
                ItemStack[] result = working.get(id);
                for (int slot = 0; slot < result.length; slot++) container.setItem(slot, result[slot].copy());
                container.setChanged();
            }
            syncClaims(request, working, metadata, changedSlots);
            for (TransferLeg leg : request.legs()) {
                rights.consumeReservation(request.authority(), leg.sourceContainerId(),
                        leg.fingerprint(), leg.quantity());
            }
            Map<UUID, Long> finalRevisions = new HashMap<>();
            for (UUID id : involved) finalRevisions.put(id, rights.bumpContext(id));
            TransferReceipt receipt = new TransferReceipt(request.transferId(),
                    TransferReceipt.Status.SUCCESS, request.intent() != TransferIntent.THEFT,
                    "", finalRevisions, completedAt);
            record(request, receipt);
            return receipt;
        } catch (RuntimeException exception) {
            for (UUID id : involved) {
                Container container = endpoints.get(id).container();
                ItemStack[] snapshot = original.get(id);
                for (int slot = 0; slot < snapshot.length; slot++) container.setItem(slot, snapshot[slot].copy());
                container.setChanged();
            }
            rights.restore(rightsSnapshot);
            return fail(request, TransferReceipt.Status.INTERNAL_FAILURE,
                    "提交失败并已回滚: " + exception.getClass().getSimpleName(), completedAt);
        }
    }

    private int physicalQuantityForOwner(ItemStack[] stacks, UUID containerId,
                                         ItemFingerprint fingerprint, SubjectRef owner,
                                         Map<SlotRef, ClaimMeta> metadata) {
        int total = 0;
        for (int slot = 0; slot < stacks.length; slot++) {
            if (!fingerprint.matches(stacks[slot])) continue;
            ClaimMeta batch = metadata.get(new SlotRef(containerId, slot));
            if (batch != null && batch.owner().equals(owner)) total += stacks[slot].getCount();
        }
        return total;
    }

    private boolean insert(ItemStack[] destination, UUID containerId, ItemStack moving,
                           ClaimMeta targetMeta, Map<SlotRef, ClaimMeta> metadata,
                           Set<SlotRef> changedSlots) {
        for (int slot = 0; slot < destination.length && !moving.isEmpty(); slot++) {
            ItemStack existing = destination[slot];
            SlotRef ref = new SlotRef(containerId, slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, moving)
                    || !targetMeta.equals(metadata.get(ref))) continue;
            int moved = Math.min(moving.getCount(), existing.getMaxStackSize() - existing.getCount());
            if (moved <= 0) continue;
            existing.grow(moved);
            moving.shrink(moved);
            changedSlots.add(ref);
        }
        for (int slot = 0; slot < destination.length && !moving.isEmpty(); slot++) {
            if (!destination[slot].isEmpty()) continue;
            int moved = Math.min(moving.getCount(), moving.getMaxStackSize());
            destination[slot] = moving.copyWithCount(moved);
            SlotRef ref = new SlotRef(containerId, slot);
            metadata.put(ref, targetMeta);
            changedSlots.add(ref);
            moving.shrink(moved);
        }
        return moving.isEmpty();
    }

    private void syncClaims(TransferRequest request, Map<UUID, ItemStack[]> working,
                            Map<SlotRef, ClaimMeta> metadata, Set<SlotRef> changedSlots) {
        for (SlotRef ref : changedSlots) {
            rights.removeClaimsForSlot(ref.containerId(), ref.slot());
            ItemStack stack = working.get(ref.containerId())[ref.slot()];
            if (stack.isEmpty()) continue;
            ContainerContext context = rights.context(ref.containerId());
            ClaimMeta actual = metadata.getOrDefault(ref, ClaimMeta.defaults(context));
            if (actual.isDefaultFor(context)) continue;
            UUID claimId = UUID.nameUUIDFromBytes((request.transferId() + ":" + ref.containerId()
                    + ":" + ref.slot()).getBytes(StandardCharsets.UTF_8));
            rights.putClaim(new AssetClaim(claimId, ref.containerId(), ref.slot(),
                    ItemFingerprint.of(stack), stack.getCount(), actual.owner(), actual.custodian(),
                    actual.encumbrances(), actual.reservations(), actual.provenance(), 0L));
        }
    }

    private static ClaimMeta targetMetadata(TransferRequest request, ClaimMeta source,
                                            ContainerContext destination) {
        return switch (request.intent()) {
            case GIFT, DELIVERY, PAYMENT -> ClaimMeta.defaults(destination);
            case ISSUE -> new ClaimMeta(source.owner(), destination.custodian(),
                    union(source.encumbrances(), "issued"), source.reservations(), request.authority());
            case LOAN -> new ClaimMeta(source.owner(), destination.custodian(),
                    union(source.encumbrances(), "loan:" + request.transferId()),
                    source.reservations(), request.authority());
            case RETURN -> new ClaimMeta(source.owner(), destination.custodian(), Set.of(),
                    source.reservations(), request.authority());
            case THEFT -> new ClaimMeta(source.owner(), destination.custodian(),
                    union(source.encumbrances(), "unlawful_possession"),
                    source.reservations(), request.authority());
        };
    }

    private static Set<String> union(Set<String> existing, String value) {
        Set<String> result = new HashSet<>(existing);
        result.add(value);
        return Set.copyOf(result);
    }

    private TransferReceipt fail(TransferRequest request, TransferReceipt.Status status,
                                 String reason, long completedAt) {
        TransferReceipt receipt = new TransferReceipt(request.transferId(), status,
                request.intent() != TransferIntent.THEFT, reason, Map.of(), completedAt);
        record(request, receipt);
        return receipt;
    }

    private void record(TransferRequest request, TransferReceipt receipt) {
        ledger.record(request, receipt, new TransferAudit(request.transferId(), request.initiatedBy(),
                request.intent(), receipt.status(), receipt.lawful(), request.authority(),
                receipt.reason(), request.legs().size(), receipt.completedAt()));
    }

    private record SlotRef(UUID containerId, int slot) {}

    private record PendingInsertion(UUID destinationContainerId, ItemStack stack,
                                    ClaimMeta metadata) {}

    private record ClaimMeta(SubjectRef owner, SubjectRef custodian, Set<String> encumbrances,
                             Set<String> reservations, String provenance) {
        private ClaimMeta {
            encumbrances = Set.copyOf(encumbrances);
            reservations = Set.copyOf(reservations);
            provenance = provenance == null ? "" : provenance;
        }

        static ClaimMeta defaults(ContainerContext context) {
            return new ClaimMeta(context.defaultBeneficialOwner(), context.custodian(),
                    Set.of(), Set.of(), "container_default");
        }

        static ClaimMeta from(AssetClaim claim) {
            return new ClaimMeta(claim.beneficialOwner(), claim.custodian(), claim.encumbrances(),
                    claim.reservations(), claim.provenance());
        }

        boolean isDefaultFor(ContainerContext context) {
            return owner.equals(context.defaultBeneficialOwner())
                    && custodian.equals(context.custodian())
                    && encumbrances.isEmpty() && reservations.isEmpty();
        }
    }
}
