package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.worldsim.rights.AssetClaim;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lists only physically present, unencumbered assets the owner is willing to expose. */
public final class TradeCatalogService {
    private TradeCatalogService() {}

    public static List<TradeCatalogEntry> visibleNpcOffers(UUID npcId, UUID containerId,
            Container inventory, RightsStateData rights, TradeSessionData sessions,
            UUID currentSession, long gameTime) {
        return visibleOwnedAssets(SubjectRef.character(npcId), containerId, inventory,
                rights, sessions, currentSession, gameTime, true);
    }

    public static List<TradeCatalogEntry> visiblePlayerOffers(UUID playerId, UUID containerId,
            Container inventory, RightsStateData rights, TradeSessionData sessions,
            UUID currentSession, long gameTime) {
        return visibleOwnedAssets(SubjectRef.character(playerId), containerId, inventory,
                rights, sessions, currentSession, gameTime, false);
    }

    private static List<TradeCatalogEntry> visibleOwnedAssets(SubjectRef owner, UUID containerId,
            Container inventory, RightsStateData rights, TradeSessionData sessions,
            UUID currentSession, long gameTime, boolean keepPersonalReserve) {
        var context = rights.context(containerId);
        if (context == null || !context.defaultBeneficialOwner().equals(owner)) return List.of();
        Map<ItemFingerprint, MutableEntry> grouped = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            ItemFingerprint fingerprint = ItemFingerprint.of(stack);
            AssetClaim claim = rights.claimAt(containerId, slot, fingerprint);
            if (claim != null && (!claim.beneficialOwner().equals(owner)
                    || !claim.encumbrances().isEmpty() || !claim.reservations().isEmpty())) continue;
            grouped.computeIfAbsent(fingerprint, ignored -> new MutableEntry(
                    stack.getHoverName().getString(), reserve(stack, keepPersonalReserve)))
                    .count += stack.getCount();
        }
        List<TradeCatalogEntry> result = new ArrayList<>();
        grouped.forEach((fingerprint, entry) -> {
            String currentToken = currentSession == null ? null : "trade:" + currentSession;
            int alreadyReserved = sessions.reservedOutsideRights(containerId, fingerprint,
                    currentSession, rights, gameTime);
            int domainReserved = rights.reservedQuantity(containerId, fingerprint,
                    owner, currentToken, gameTime);
            int reservedHere = currentToken == null ? 0 : rights.reservationsForToken(
                    currentToken, gameTime).stream()
                    .filter(value -> value.containerId().equals(containerId)
                            && value.fingerprint().equals(fingerprint))
                    .mapToInt(value -> value.quantity()).sum();
            int available = Math.max(0, entry.count - entry.reserve - alreadyReserved - domainReserved);
            if (available > 0) result.add(new TradeCatalogEntry(fingerprint, entry.count,
                    available, Math.min(reservedHere, available), entry.name,
                    quantityReason(entry.reserve, alreadyReserved + domainReserved, reservedHere)));
        });
        return result.stream().limit(27).toList();
    }

    private static String quantityReason(int personalReserve, int committedElsewhere,
                                         int reservedHere) {
        List<String> details = new ArrayList<>();
        if (personalReserve > 0) details.add("个人保留 " + personalReserve);
        if (committedElsewhere > 0) details.add("其他承诺 " + committedElsewhere);
        if (reservedHere > 0) details.add("本次谈判已预留 " + reservedHere);
        return details.isEmpty() ? "全部为自有且未预留" : String.join(" · ", details);
    }

    private static int reserve(ItemStack stack, boolean enabled) {
        if (!enabled) return 0;
        if (stack.has(DataComponents.FOOD)) return 3;
        if (stack.getItem() instanceof ArrowItem) return 8;
        return 0;
    }

    private static final class MutableEntry {
        private final String name;
        private final int reserve;
        private int count;

        private MutableEntry(String name, int reserve) {
            this.name = name;
            this.reserve = reserve;
        }
    }
}
