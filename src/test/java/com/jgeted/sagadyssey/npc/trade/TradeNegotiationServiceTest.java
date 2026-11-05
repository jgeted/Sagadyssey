package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ConsumptionService;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeNegotiationServiceTest {
    @Test
    void staleProposalRevisionAndReservedStockAreRejected() {
        TradeSessionData data = new TradeSessionData();
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID pc = UUID.randomUUID();
        UUID nc = UUID.randomUUID();
        TradeSession session = data.open(player, npc, pc, nc, 0L);
        SimpleContainer playerBag = new SimpleContainer(2);
        SimpleContainer npcBag = new SimpleContainer(2);
        playerBag.setItem(0, new ItemStack(Items.EMERALD, 8));
        npcBag.setItem(0, new ItemStack(Items.IRON_HOE));
        Map<UUID, TransferEndpoint> endpoints = Map.of(pc, new TransferEndpoint(pc, playerBag),
                nc, new TransferEndpoint(nc, npcBag));
        List<TradeAsset> gives = List.of(new TradeAsset(
                ItemFingerprint.of(new ItemStack(Items.EMERALD)), 8, "绿宝石"));
        List<TradeAsset> takes = List.of(new TradeAsset(
                ItemFingerprint.of(new ItemStack(Items.IRON_HOE)), 1, "铁锄"));

        TradeSession accepted = TradeNegotiationService.propose(data, session.id(), 0,
                player, TradeIntent.SPOT_PURCHASE, gives, takes, endpoints, 10L);

        assertEquals(TradeSessionStatus.ACCEPTED, accepted.status());
        assertThrows(IllegalStateException.class, () -> TradeNegotiationService.propose(data,
                session.id(), 0, player, TradeIntent.SPOT_PURCHASE, gives, takes, endpoints, 11L));
    }

    @Test
    void secondBuyerCannotReserveTheSameLastItem() {
        TradeSessionData data = new TradeSessionData();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID firstBagId = UUID.randomUUID();
        UUID secondBagId = UUID.randomUUID();
        UUID npcBagId = UUID.randomUUID();
        TradeSession first = data.open(firstPlayer, npc, firstBagId, npcBagId, 0L);
        TradeSession second = data.open(secondPlayer, npc, secondBagId, npcBagId, 0L);
        SimpleContainer firstBag = new SimpleContainer(2);
        SimpleContainer secondBag = new SimpleContainer(2);
        SimpleContainer npcBag = new SimpleContainer(2);
        firstBag.setItem(0, new ItemStack(Items.EMERALD, 8));
        secondBag.setItem(0, new ItemStack(Items.EMERALD, 8));
        npcBag.setItem(0, new ItemStack(Items.IRON_HOE));
        List<TradeAsset> money = List.of(new TradeAsset(
                ItemFingerprint.of(new ItemStack(Items.EMERALD)), 8, "绿宝石"));
        List<TradeAsset> hoe = List.of(new TradeAsset(
                ItemFingerprint.of(new ItemStack(Items.IRON_HOE)), 1, "铁锄"));
        TradeNegotiationService.propose(data, first.id(), 0, firstPlayer,
                TradeIntent.SPOT_PURCHASE, money, hoe,
                Map.of(firstBagId, new TransferEndpoint(firstBagId, firstBag),
                        npcBagId, new TransferEndpoint(npcBagId, npcBag)), 10L);

        assertThrows(IllegalStateException.class, () -> TradeNegotiationService.propose(data,
                second.id(), 0, secondPlayer, TradeIntent.SPOT_PURCHASE, money, hoe,
                Map.of(secondBagId, new TransferEndpoint(secondBagId, secondBag),
                        npcBagId, new TransferEndpoint(npcBagId, npcBag)), 11L));
    }

    @Test
    void identicalAssetOnBothSidesIsRejectedAsMeaninglessExchange() {
        TradeSessionData data = new TradeSessionData();
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID playerBagId = UUID.randomUUID();
        UUID npcBagId = UUID.randomUUID();
        TradeSession session = data.open(player, npc, playerBagId, npcBagId, 0L);
        SimpleContainer playerBag = new SimpleContainer(new ItemStack(Items.EMERALD, 4));
        SimpleContainer npcBag = new SimpleContainer(new ItemStack(Items.EMERALD, 3));
        TradeAsset emerald = new TradeAsset(ItemFingerprint.of(new ItemStack(Items.EMERALD)),
                1, "绿宝石");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TradeNegotiationService.propose(data, session.id(), 0, player,
                        TradeIntent.SPOT_PURCHASE, List.of(emerald), List.of(emerald),
                        Map.of(playerBagId, new TransferEndpoint(playerBagId, playerBag),
                                npcBagId, new TransferEndpoint(npcBagId, npcBag)), 10L));

        assertTrue(error.getMessage().contains("同一种物品"));
        assertEquals(TradeSessionStatus.OPEN, data.session(session.id()).status());
        assertEquals(4, playerBag.getItem(0).getCount());
        assertEquals(3, npcBag.getItem(0).getCount());
    }

    @Test
    void counterofferCreatesGlobalReservationVisibleToConsumptionAndRefusalReleasesIt() {
        TradeSessionData data = new TradeSessionData();
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID playerBagId = UUID.randomUUID();
        UUID npcBagId = UUID.randomUUID();
        TradeSession session = data.open(player, npc, playerBagId, npcBagId, 0L);
        SimpleContainer playerBag = new SimpleContainer(3);
        SimpleContainer npcBag = new SimpleContainer(3);
        playerBag.setItem(0, new ItemStack(Items.EMERALD));
        playerBag.setItem(1, new ItemStack(Items.COBBLESTONE));
        npcBag.setItem(0, new ItemStack(Items.IRON_HOE));
        npcBag.setItem(1, new ItemStack(Items.COBBLESTONE, 8));
        RightsStateData rights = new RightsStateData();
        rights.ensureContext(playerBagId, "player", SubjectRef.character(player),
                SubjectRef.character(player), AccessPolicy.PRIVATE);
        rights.ensureContext(npcBagId, "npc", SubjectRef.character(npc),
                SubjectRef.character(npc), AccessPolicy.PRIVATE);
        Map<UUID, TransferEndpoint> endpoints = Map.of(
                playerBagId, new TransferEndpoint(playerBagId, playerBag),
                npcBagId, new TransferEndpoint(npcBagId, npcBag));
        ItemFingerprint hoe = ItemFingerprint.of(new ItemStack(Items.IRON_HOE));

        TradeSession countered = TradeNegotiationService.propose(data, session.id(), 0,
                player, TradeIntent.SPOT_PURCHASE,
                List.of(new TradeAsset(ItemFingerprint.of(new ItemStack(Items.EMERALD)), 1, "绿宝石")),
                List.of(new TradeAsset(hoe, 1, "铁锄")), endpoints, 10L,
                TradeEvaluationContext.unobserved(10L), rights);

        assertEquals(TradeSessionStatus.COUNTERED, countered.status());
        TradeCatalogEntry visibleHoe = TradeCatalogService.visibleNpcOffers(npc, npcBagId,
                npcBag, rights, data, session.id(), 11L).stream()
                .filter(entry -> entry.fingerprint().equals(hoe)).findFirst().orElseThrow();
        assertEquals(1, visibleHoe.owned());
        assertEquals(1, visibleHoe.available());
        assertEquals(1, visibleHoe.reservedHere());
        assertEquals(ConsumptionService.Status.RESERVED,
                new ConsumptionService(rights).consume(npcBagId, npcBag,
                        SubjectRef.character(npc), hoe, 1, "repair", 11L).status());

        TradeSession refused = TradeNegotiationService.propose(data, session.id(), 1,
                player, TradeIntent.BARTER,
                List.of(new TradeAsset(ItemFingerprint.of(new ItemStack(Items.COBBLESTONE)), 1, "圆石")),
                List.of(new TradeAsset(hoe, 1, "铁锄")), endpoints, 12L,
                TradeEvaluationContext.unobserved(12L), rights);
        assertEquals(TradeSessionStatus.OPEN, refused.status());
        assertEquals(0, rights.reservedQuantity(npcBagId, hoe, null, 13L));
    }
}
