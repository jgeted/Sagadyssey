package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.npc.trade.TradeAsset;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmitTradeProposalPacketTest {
    @Test
    void multiLineOfferRoundTripsWithoutLosingEitherBasket() {
        SubmitTradeProposalPacket original = new SubmitTradeProposalPacket(17, UUID.randomUUID(), 4,
                List.of(new TradeAsset(new ItemFingerprint("minecraft:emerald", 0), 3, "绿宝石"),
                        new TradeAsset(new ItemFingerprint("minecraft:bread", 0), 2, "面包")),
                List.of(new TradeAsset(new ItemFingerprint("minecraft:paper", 0), 2, "纸"),
                        new TradeAsset(new ItemFingerprint("minecraft:book", 0), 1, "书")));
        var buffer = Unpooled.buffer();

        SubmitTradeProposalPacket.STREAM_CODEC.encode(buffer, original);
        SubmitTradeProposalPacket decoded = SubmitTradeProposalPacket.STREAM_CODEC.decode(buffer);

        assertEquals(original, decoded);
        buffer.release();
    }
}
