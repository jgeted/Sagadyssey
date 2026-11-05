package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeSessionDataTest {
    @Test
    void activeProposalReservesNpcStockUntilTerminalOrExpired() {
        TradeSessionData data = new TradeSessionData();
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID playerContainer = UUID.randomUUID();
        UUID npcContainer = UUID.randomUUID();
        TradeSession open = data.open(player, npc, playerContainer, npcContainer, 0L);
        ItemFingerprint hoe = ItemFingerprint.of(new ItemStack(Items.IRON_HOE));
        TradeProposal proposal = new TradeProposal(UUID.randomUUID(), open.id(), 1, player,
                TradeIntent.BARTER,
                List.of(new TradeAsset(ItemFingerprint.of(new ItemStack(Items.COAL)), 8, "煤炭")),
                List.of(new TradeAsset(hoe, 1, "铁锄")), 10L, 100L);
        TradeSession proposed = open.withProposal(proposal, TradeSessionStatus.PROPOSED, "等待答复");
        data.put(proposed);

        assertEquals(1, data.reserved(npcContainer, hoe, UUID.randomUUID()));
        assertEquals(0, data.reserved(npcContainer, hoe, open.id()));

        data.expire(1_201L);
        assertEquals(0, data.reserved(npcContainer, hoe, UUID.randomUUID()));
        assertEquals(TradeSessionStatus.EXPIRED, data.session(open.id()).status());
    }
}
