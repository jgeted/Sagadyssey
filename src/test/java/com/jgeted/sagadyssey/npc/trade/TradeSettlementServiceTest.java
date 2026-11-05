package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.ExpectedRevision;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeSettlementServiceTest {
    @Test
    void multiLineBundleMovesEveryAssetOnBothSidesAsOneExchange() {
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID playerContainer = UUID.randomUUID();
        UUID npcContainer = UUID.randomUUID();
        SimpleContainer playerBag = new SimpleContainer(8);
        SimpleContainer npcBag = new SimpleContainer(8);
        playerBag.setItem(0, new ItemStack(Items.EMERALD, 3));
        playerBag.setItem(1, new ItemStack(Items.BREAD, 2));
        npcBag.setItem(0, new ItemStack(Items.IRON_HOE));
        npcBag.setItem(1, new ItemStack(Items.COAL, 4));
        RightsStateData rights = new RightsStateData();
        register(rights, playerContainer, player);
        register(rights, npcContainer, npc);
        TradeSession accepted = acceptedTrade(player, npc, playerContainer, npcContainer,
                List.of(asset(Items.EMERALD, 3, "绿宝石"), asset(Items.BREAD, 2, "面包")),
                List.of(asset(Items.IRON_HOE, 1, "铁锄"), asset(Items.COAL, 4, "煤炭")), 0L);

        var receipt = TradeSettlementService.commit(accepted,
                Map.of(playerContainer, new TransferEndpoint(playerContainer, playerBag),
                        npcContainer, new TransferEndpoint(npcContainer, npcBag)),
                rights, new TransferLedgerData(), 20L);

        assertTrue(receipt.successful());
        assertEquals(0, count(playerBag, Items.EMERALD));
        assertEquals(0, count(playerBag, Items.BREAD));
        assertEquals(1, count(playerBag, Items.IRON_HOE));
        assertEquals(4, count(playerBag, Items.COAL));
        assertEquals(3, count(npcBag, Items.EMERALD));
        assertEquals(2, count(npcBag, Items.BREAD));
        assertEquals(0, count(npcBag, Items.IRON_HOE));
        assertEquals(0, count(npcBag, Items.COAL));
    }

    @Test
    void acceptedTradeMovesOnlyExistingAssetsAndConservesBothItems() {
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID playerContainer = UUID.randomUUID();
        UUID npcContainer = UUID.randomUUID();
        SimpleContainer playerBag = new SimpleContainer(2);
        SimpleContainer npcBag = new SimpleContainer(2);
        playerBag.setItem(0, new ItemStack(Items.EMERALD, 3));
        npcBag.setItem(0, new ItemStack(Items.IRON_HOE));
        RightsStateData rights = new RightsStateData();
        rights.ensureContext(playerContainer, "player", SubjectRef.character(player),
                SubjectRef.character(player), AccessPolicy.PRIVATE);
        rights.ensureContext(npcContainer, "npc", SubjectRef.character(npc),
                SubjectRef.character(npc), AccessPolicy.PRIVATE);
        TradeSession open = TradeSession.open(player, npc, playerContainer, npcContainer, 0L, 600L);
        TradeProposal proposal = new TradeProposal(UUID.randomUUID(), open.id(), 1, player,
                TradeIntent.SPOT_PURCHASE,
                List.of(new TradeAsset(ItemFingerprint.of(new ItemStack(Items.EMERALD)), 3, "绿宝石")),
                List.of(new TradeAsset(ItemFingerprint.of(new ItemStack(Items.IRON_HOE)), 1, "铁锄")),
                10L, 200L);
        TradeSession accepted = open.withProposal(proposal, TradeSessionStatus.ACCEPTED, "双方同意");

        var receipt = TradeSettlementService.commit(accepted,
                Map.of(playerContainer, new TransferEndpoint(playerContainer, playerBag),
                        npcContainer, new TransferEndpoint(npcContainer, npcBag)),
                rights, new TransferLedgerData(), 20L);

        assertTrue(receipt.successful());
        assertTrue(playerBag.getItem(0).is(Items.IRON_HOE));
        assertEquals(3, count(npcBag, Items.EMERALD));
        assertEquals(1, count(playerBag, Items.IRON_HOE));
    }

    @Test
    void soldOutNpcCannotCommitAStaleAcceptedProposal() {
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID pc = UUID.randomUUID();
        UUID nc = UUID.randomUUID();
        SimpleContainer playerBag = new SimpleContainer(2);
        SimpleContainer npcBag = new SimpleContainer(2);
        playerBag.setItem(0, new ItemStack(Items.EMERALD));
        RightsStateData rights = new RightsStateData();
        rights.ensureContext(pc, "player", SubjectRef.character(player),
                SubjectRef.character(player), AccessPolicy.PRIVATE);
        rights.ensureContext(nc, "npc", SubjectRef.character(npc),
                SubjectRef.character(npc), AccessPolicy.PRIVATE);
        TradeSession open = TradeSession.open(player, npc, pc, nc, 0L, 600L);
        TradeSession accepted = open.withProposal(new TradeProposal(UUID.randomUUID(), open.id(), 1,
                player, TradeIntent.SPOT_PURCHASE,
                List.of(new TradeAsset(ItemFingerprint.of(new ItemStack(Items.EMERALD)), 1, "绿宝石")),
                List.of(new TradeAsset(ItemFingerprint.of(new ItemStack(Items.IRON_HOE)), 1, "铁锄")),
                10L, 200L), TradeSessionStatus.ACCEPTED, "双方同意");

        var receipt = TradeSettlementService.commit(accepted,
                Map.of(pc, new TransferEndpoint(pc, playerBag),
                        nc, new TransferEndpoint(nc, npcBag)), rights,
                new TransferLedgerData(), 20L);

        assertFalse(receipt.successful());
        assertEquals(1, count(playerBag, Items.EMERALD));
    }

    @Test
    void merchantProfitRequiresBuyingCarryingAndResellingRealStock() {
        UUID seller = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID sellerContainer = UUID.randomUUID();
        UUID merchantContainer = UUID.randomUUID();
        UUID buyerContainer = UUID.randomUUID();
        SimpleContainer sellerBag = new SimpleContainer(4);
        SimpleContainer merchantBag = new SimpleContainer(4);
        SimpleContainer buyerBag = new SimpleContainer(4);
        sellerBag.setItem(0, new ItemStack(Items.COAL, 4));
        merchantBag.setItem(0, new ItemStack(Items.EMERALD, 2));
        buyerBag.setItem(0, new ItemStack(Items.EMERALD, 4));
        RightsStateData rights = new RightsStateData();
        register(rights, sellerContainer, seller);
        register(rights, merchantContainer, merchant);
        register(rights, buyerContainer, buyer);
        TransferLedgerData ledger = new TransferLedgerData();

        TradeSession purchase = acceptedTrade(seller, merchant, sellerContainer, merchantContainer,
                List.of(asset(Items.COAL, 4, "煤炭")),
                List.of(asset(Items.EMERALD, 2, "绿宝石")), 0L);
        var purchaseReceipt = TradeSettlementService.commit(purchase,
                Map.of(sellerContainer, new TransferEndpoint(sellerContainer, sellerBag),
                        merchantContainer, new TransferEndpoint(merchantContainer, merchantBag)),
                rights, ledger, 20L);

        assertTrue(purchaseReceipt.successful());
        assertEquals(4, count(merchantBag, Items.COAL));
        assertEquals(0, count(merchantBag, Items.EMERALD));

        TradeSession resale = acceptedTrade(buyer, merchant, buyerContainer, merchantContainer,
                List.of(asset(Items.EMERALD, 4, "绿宝石")),
                List.of(asset(Items.COAL, 4, "煤炭")), 30L);
        var resaleReceipt = TradeSettlementService.commit(resale,
                Map.of(buyerContainer, new TransferEndpoint(buyerContainer, buyerBag),
                        merchantContainer, new TransferEndpoint(merchantContainer, merchantBag)),
                rights, ledger, 40L);

        assertTrue(resaleReceipt.successful());
        assertEquals(4, count(buyerBag, Items.COAL));
        assertEquals(4, count(merchantBag, Items.EMERALD));
        assertEquals(2, count(sellerBag, Items.EMERALD));
        assertEquals(6, count(sellerBag, Items.EMERALD) + count(merchantBag, Items.EMERALD)
                + count(buyerBag, Items.EMERALD));
        assertEquals(4, count(sellerBag, Items.COAL) + count(merchantBag, Items.COAL)
                + count(buyerBag, Items.COAL));
    }

    @Test
    void robberyImmediatelyReducesVictimSupplyWithoutMakingLootLawfullySaleable() {
        UUID victim = UUID.randomUUID();
        UUID robber = UUID.randomUUID();
        UUID victimContainer = UUID.randomUUID();
        UUID robberContainer = UUID.randomUUID();
        SimpleContainer victimBag = new SimpleContainer(4);
        SimpleContainer robberBag = new SimpleContainer(4);
        ItemFingerprint coal = ItemFingerprint.of(new ItemStack(Items.COAL));
        victimBag.setItem(0, new ItemStack(Items.COAL, 5));
        RightsStateData rights = new RightsStateData();
        register(rights, victimContainer, victim);
        register(rights, robberContainer, robber);
        TransferRequest theft = new TransferRequest(UUID.randomUUID(), SubjectRef.character(robber),
                TransferIntent.THEFT,
                List.of(new TransferLeg(victimContainer, robberContainer, coal, 3)),
                List.of(new ExpectedRevision(victimContainer, 0L),
                        new ExpectedRevision(robberContainer, 0L)),
                "raid:test", 10L);

        var receipt = new TransferService(rights, new TransferLedgerData()).submit(theft,
                Map.of(victimContainer, new TransferEndpoint(victimContainer, victimBag),
                        robberContainer, new TransferEndpoint(robberContainer, robberBag)), 10L);

        assertTrue(receipt.successful());
        var victimOffers = TradeCatalogService.visibleNpcOffers(victim, victimContainer, victimBag,
                rights, new TradeSessionData(), null, 11L);
        var robberOffers = TradeCatalogService.visibleNpcOffers(robber, robberContainer, robberBag,
                rights, new TradeSessionData(), null, 11L);
        assertEquals(2, victimOffers.stream().filter(entry -> entry.fingerprint().equals(coal))
                .mapToInt(TradeCatalogEntry::available).sum());
        assertTrue(robberOffers.isEmpty(), "stolen title remains with the victim until fenced or adjudicated");
    }

    private static TradeSession acceptedTrade(UUID player, UUID npc, UUID playerContainer,
                                              UUID npcContainer, List<TradeAsset> playerOffers,
                                              List<TradeAsset> npcOffers, long openedAt) {
        TradeSession open = TradeSession.open(player, npc, playerContainer, npcContainer,
                openedAt, 600L);
        TradeProposal proposal = new TradeProposal(UUID.randomUUID(), open.id(), 1, player,
                TradeIntent.SPOT_PURCHASE, playerOffers, npcOffers, openedAt + 1, openedAt + 500);
        return open.withProposal(proposal, TradeSessionStatus.ACCEPTED, "双方同意");
    }

    private static TradeAsset asset(net.minecraft.world.item.Item item, int quantity, String name) {
        return new TradeAsset(ItemFingerprint.of(new ItemStack(item)), quantity, name);
    }

    private static void register(RightsStateData rights, UUID container, UUID owner) {
        rights.ensureContext(container, "test", SubjectRef.character(owner),
                SubjectRef.character(owner), AccessPolicy.PRIVATE);
    }

    private static int count(SimpleContainer container, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
        }
        return total;
    }
}
