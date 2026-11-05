package com.jgeted.sagadyssey.npc.handover;

import com.jgeted.sagadyssey.knowledge.DisclosurePolicy;
import com.jgeted.sagadyssey.knowledge.FactKey;
import com.jgeted.sagadyssey.knowledge.KnowledgeStateData;
import com.jgeted.sagadyssey.knowledge.PrecisionBand;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcMemoryRecord;
import com.jgeted.sagadyssey.social.life.NpcRelationKind;
import com.jgeted.sagadyssey.social.service.AuthorityScope;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fast world-space handover backed by the same rights and transfer ledger as wages and logistics. */
public final class NpcHandoverService {
    private NpcHandoverService() {}

    public static HandoverDecision offer(ServerPlayer player, NpcBase npc, InteractionHand hand,
                                         boolean wholeStack, @Nullable HandoverIntent requestedIntent,
                                         UUID transferId) {
        if (!npc.isAlive() || player.distanceToSqr(npc) > 36.0D) {
            return refused("距离过远，无法完成递交");
        }
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return refused("手中没有可递交的物品");
        int quantity = wholeStack ? held.getCount() : 1;
        // Keep an immutable description of exactly what was offered. TransferService mutates the
        // live player inventory, so using that stack afterwards can turn receipts and memories into
        // an "air" handover when the last item was moved.
        ItemStack offered = held.copy();
        offered.setCount(quantity);
        UUID giverId = CharacterRegistry.ensureCurrent(player).id();
        long now = player.serverLevel().getGameTime();
        boolean mayIssue = ServiceRelationshipData.get(player.server).hasScope(giverId,
                npc.getCharacterId(), AuthorityScope.MANAGE_ISSUED_EQUIPMENT, now);
        HandoverIntent intent = requestedIntent == null ? inferIntent(offered, mayIssue) : requestedIntent;

        if (intent == null) {
            return new HandoverDecision(HandoverDecision.Status.NEEDS_CLARIFICATION, null, 0,
                    "这件物品可能是赠礼、借用或配发；请从人物卡选择递交意图", null);
        }
        String refusal = refusalFor(intent, offered, mayIssue);
        if (refusal != null) return refused(refusal);
        if (intent == HandoverIntent.SHOW) {
            recordShown(player, npc, giverId, offered, now);
            return new HandoverDecision(HandoverDecision.Status.SHOWN, intent, 0,
                    "对方看见了你展示的 " + offered.getHoverName().getString(), null);
        }

        RightsStateData rights = RightsStateData.get(player.server);
        SubjectRef giver = SubjectRef.character(giverId);
        SubjectRef receiver = SubjectRef.character(npc.getCharacterId());
        UUID sourceId = ContainerIds.playerPersonal(giverId);
        UUID destinationId = ContainerIds.npcPersonal(npc.getCharacterId());
        rights.ensureContext(sourceId, "player:" + giverId + ":personal",
                giver, giver, AccessPolicy.PRIVATE);
        rights.ensureContext(destinationId, "npc:" + npc.getCharacterId() + ":personal",
                receiver, receiver, AccessPolicy.PRIVATE);
        TransferRequest request = new TransferRequest(transferId, giver, map(intent),
                List.of(new TransferLeg(sourceId, destinationId, ItemFingerprint.of(offered), quantity)),
                List.of(new ExpectedRevision(sourceId, rights.context(sourceId).revision()),
                        new ExpectedRevision(destinationId, rights.context(destinationId).revision())),
                "world_handover:" + intent.name().toLowerCase(java.util.Locale.ROOT), now);
        var receipt = new TransferService(rights, TransferLedgerData.get(player.server))
                .submit(request, Map.of(sourceId, new TransferEndpoint(sourceId, player.getInventory()),
                        destinationId, new TransferEndpoint(destinationId,
                                npc.getEquipmentInventory())), now);
        if (!receipt.successful()) return refused(disclosedFailure(receipt.status(), receipt.reason()));

        applySocialEffects(player, npc, giverId, offered, quantity, intent, now);
        npc.playAction(NpcAction.TRADE_PRESENT, 24);
        return new HandoverDecision(HandoverDecision.Status.ACCEPTED, intent, quantity,
                "已以“" + intent.displayName() + "”递交 " + quantity + " 个 "
                        + offered.getHoverName().getString(), transferId);
    }

    @Nullable
    static HandoverIntent inferIntent(ItemStack stack, boolean mayIssue) {
        if (equipmentLike(stack)) return mayIssue ? HandoverIntent.ISSUE : null;
        return HandoverIntent.GIFT;
    }

    static TransferIntent map(HandoverIntent intent) {
        return switch (intent) {
            case GIFT -> TransferIntent.GIFT;
            case PAYMENT -> TransferIntent.PAYMENT;
            case DELIVERY -> TransferIntent.DELIVERY;
            case ISSUE -> TransferIntent.ISSUE;
            case LOAN -> TransferIntent.LOAN;
            case SHOW -> throw new IllegalArgumentException("SHOW does not transfer title or possession");
        };
    }

    private static boolean equipmentLike(ItemStack stack) {
        return stack.getItem() instanceof SwordItem || stack.getItem() instanceof DiggerItem
                || stack.getItem() instanceof ProjectileWeaponItem
                || stack.getItem() instanceof ArmorItem || stack.getItem() instanceof ShieldItem;
    }

    static boolean dangerous(ItemStack stack) {
        return stack.is(Items.TNT) || stack.is(Items.TNT_MINECART) || stack.is(Items.END_CRYSTAL)
                || stack.is(Items.LAVA_BUCKET) || stack.is(Items.FIRE_CHARGE);
    }

    @Nullable
    static String refusalFor(HandoverIntent intent, ItemStack stack, boolean mayIssue) {
        if (dangerous(stack)) return "对方拒绝接收明显危险的物品";
        if (intent == HandoverIntent.ISSUE && !mayIssue) {
            return "你没有向此人配发装备的服务授权";
        }
        if (intent == HandoverIntent.PAYMENT && !stack.is(Items.EMERALD)) {
            return "当前约定只把绿宝石视作可核验的实物付款";
        }
        if (intent == HandoverIntent.LOAN && stack.has(DataComponents.FOOD)) {
            return "可消耗食物不能作为可归还借用品";
        }
        return null;
    }

    private static void recordShown(ServerPlayer player, NpcBase npc, UUID giverId,
                                    ItemStack held, long now) {
        String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        KnowledgeStateData.get(player.server).observe(npc.getCharacterId(),
                new FactKey(giverId, "shown.item", itemId), held.getHoverName().getString(),
                PrecisionBand.EXACT, 1.0D, "当面展示", now, 6_000L,
                DisclosurePolicy.PRIVATE);
        npc.playAction(NpcAction.HEAD_NOD, 20);
    }

    private static void applySocialEffects(ServerPlayer player, NpcBase npc, UUID giverId,
                                           ItemStack item, int quantity, HandoverIntent intent,
                                           long now) {
        NpcLifeData life = NpcLifeData.get(player.server);
        int goodwill = switch (intent) {
            case GIFT -> item.has(DataComponents.FOOD) ? 5 : 3;
            case PAYMENT -> 2;
            case DELIVERY, ISSUE, LOAN -> 1;
            case SHOW -> 0;
        };
        if (goodwill > 0) life.adjustRelationship(npc.getCharacterId(), giverId,
                NpcRelationKind.FRIEND, goodwill,
                "收到" + intent.displayName() + "物品：" + item.getHoverName().getString());
        life.remember(npc.getCharacterId(), new NpcMemoryRecord("handover_"
                + intent.name().toLowerCase(java.util.Locale.ROOT), giverId,
                "从 " + player.getName().getString() + " 收到 " + quantity + " 个 "
                        + item.getHoverName().getString() + "（" + intent.displayName() + "）",
                Math.min(45, 8 + goodwill * 5), 55, now, now + 72_000L), now);
    }

    private static String disclosedFailure(com.jgeted.sagadyssey.worldsim.transfer.TransferReceipt.Status status,
                                           String internalReason) {
        return switch (status) {
            case CAPACITY_EXCEEDED -> "对方没有足够空间接收这些物品";
            case INSUFFICIENT_ITEMS, STALE_REVISION -> "你手中的物品已经变化，请重试";
            case RIGHTS_DENIED, RIGHTS_CONTESTED -> "你没有可核验的处分权，或该物品权属存在争议";
            default -> internalReason.isBlank() ? "递交未能完成" : internalReason;
        };
    }

    private static HandoverDecision refused(String reason) {
        return new HandoverDecision(HandoverDecision.Status.REFUSED, null, 0, reason, null);
    }
}
