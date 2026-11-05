package com.jgeted.sagadyssey.npc.faction.network;

import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.npc.faction.StandingLevel;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactionPayloadCodecTest {
    @Test
    void fullAndIncrementalFactionViewsRoundTripWithinBounds() {
        FactionDataSyncPayload full = new FactionDataSyncPayload(
                Map.of("sagadyssey:kingdom", -40),
                Set.of("sagadyssey:player", "sagadyssey:kingdom"),
                Map.of("sagadyssey:kingdom→sagadyssey:bandit", "ENEMY"), "河谷盟约");
        var fullBuffer = Unpooled.buffer();
        FactionDataSyncPayload.STREAM_CODEC.encode(fullBuffer, full);
        FactionDataSyncPayload decodedFull = FactionDataSyncPayload.STREAM_CODEC.decode(fullBuffer);
        assertEquals(full, decodedFull);
        assertEquals(Set.of("sagadyssey:player", "sagadyssey:kingdom"),
                decodedFull.knownFactions());
        fullBuffer.release();

        FactionStandingsUpdatePayload update = new FactionStandingsUpdatePayload(
                "sagadyssey:kingdom", -40, StandingLevel.COLD, "trade",
                List.of(new FactionStandingsUpdatePayload.AffectedFaction(
                        "sagadyssey:merchant_guild", 2, 12, StandingLevel.NEUTRAL)));
        var updateBuffer = Unpooled.buffer();
        FactionStandingsUpdatePayload.STREAM_CODEC.encode(updateBuffer, update);
        assertEquals(update, FactionStandingsUpdatePayload.STREAM_CODEC.decode(updateBuffer));
        updateBuffer.release();
    }

    @Test
    void malformedCountsAndStandingLevelsAreRejectedBeforeAllocation() {
        var oversized = Unpooled.buffer();
        oversized.writeInt(129);
        assertThrows(DecoderException.class,
                () -> FactionDataSyncPayload.STREAM_CODEC.decode(oversized));
        oversized.release();

        var invalidLevel = Unpooled.buffer();
        NetworkStrings.write(invalidLevel, "sagadyssey:kingdom", 128);
        invalidLevel.writeInt(0);
        invalidLevel.writeByte(99);
        assertThrows(DecoderException.class,
                () -> FactionStandingsUpdatePayload.STREAM_CODEC.decode(invalidLevel));
        invalidLevel.release();
    }
}
