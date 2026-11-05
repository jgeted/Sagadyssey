package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.npc.trade.TradeCatalogEntry;
import com.jgeted.sagadyssey.npc.trade.TradeAsset;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeSessionPayloadTest {
    @Test
    void observerSafeCatalogRoundTripsStableFingerprintsAndQuantities() {
        TradeSessionPayload original = new TradeSessionPayload(42, UUID.randomUUID(), 3,
                "玛拉", "COUNTERED", "木炭有用，但数量还不够。",
                List.of(new TradeCatalogEntry(new ItemFingerprint("minecraft:coal", 0),
                        16, 12, 3, "煤炭", "个人保留 1 · 本次谈判已预留 3")),
                List.of(new TradeCatalogEntry(new ItemFingerprint("minecraft:iron_hoe", 0),
                        1, 1, 0, "铁锄", "全部为自有且未预留")),
                List.of(new TradeAsset(new ItemFingerprint("minecraft:coal", 0), 4, "煤炭"),
                        new TradeAsset(new ItemFingerprint("minecraft:emerald", 0), 2, "绿宝石")),
                List.of(new TradeAsset(new ItemFingerprint("minecraft:iron_hoe", 0), 1, "铁锄")));
        var buffer = Unpooled.buffer();

        TradeSessionPayload.STREAM_CODEC.encode(buffer, original);
        TradeSessionPayload decoded = TradeSessionPayload.STREAM_CODEC.decode(buffer);

        assertEquals(original, decoded);
        buffer.release();
    }
}
