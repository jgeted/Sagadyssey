package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LifeActivityDataTest {
    @Test void receiptsSurviveRoundtripWithoutReopeningCompletedSettlement() {
        var d=new LifeActivityData();d.pending("eat");d.done("eat");d.pending("review");
        var loaded=LifeActivityData.load(d.save(new CompoundTag(),null),null);
        assertEquals("DONE",loaded.settlement("eat"));assertEquals("PENDING",loaded.settlement("review"));
        loaded.pending("eat");assertEquals("DONE",loaded.settlement("eat"));
    }
    @Test void deferredKnownPlacesStayActorSpecific() {
        var d=new LifeActivityData();var npc=UUID.randomUUID();var place=new LifeActivityData.Place("minecraft:overworld",BlockPos.ZERO,"sagadyssey:read");
        d.reveal(npc,place);d.reveal(npc,place);d.defer(npc,100);
        var loaded=LifeActivityData.load(d.save(new CompoundTag(),null),null);
        assertEquals(1,loaded.knownPlaces(npc).size());assertTrue(loaded.knownPlaces(UUID.randomUUID()).isEmpty());
        assertEquals(300,loaded.retryAt(npc));assertThrows(UnsupportedOperationException.class,()->loaded.knownPlaces(npc).clear());
    }
    @Test void runningConversationReconfirmsPresenceOnceAfterLoad() {
        var d=new LifeActivityData();var id=UUID.randomUUID();
        d.invite(ConversationSession.invite(id,UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,100,10,1000).tick(20,true,true));
        var loaded=LifeActivityData.load(d.save(new CompoundTag(),null),null);
        assertEquals(ConversationSession.Status.INVITED,loaded.session(id,500).status());
        loaded.session(loaded.session(id,500).tick(500,true,true));
        assertEquals(500,loaded.session(id,501).startedAt());
    }
    @Test void realActivityClockPersistsAndCanRestartWithoutOfflineCredit() {
        var d=new LifeActivityData();assertEquals(10,d.start("sleep",10));assertEquals(10,d.start("sleep",20));
        var loaded=LifeActivityData.load(d.save(new CompoundTag(),null),null);
        assertEquals(10,loaded.start("sleep",200));loaded.restart("sleep",200);assertEquals(200,loaded.start("sleep",201));
    }
    @Test void corruptFieldsAndDuplicateReceiptsRejected() {
        var d=new LifeActivityData();d.pending("x");var tag=d.save(new CompoundTag(),null);
        MinecraftApi.listOfType(tag, "Settlements",Tag.TAG_COMPOUND).add(MinecraftApi.listOfType(tag, "Settlements",Tag.TAG_COMPOUND).get(0).copy());
        assertThrows(IllegalArgumentException.class,()->LifeActivityData.load(tag,null));
        var missing=d.save(new CompoundTag(),null);missing.remove("Sessions");
        assertThrows(IllegalArgumentException.class,()->LifeActivityData.load(missing,null));
    }
    @Test void failedAttemptCanAbortButFinishedReceiptCannotBeErasedByAbort() {
        var d=new LifeActivityData();d.pending("x");d.abort("x");assertNull(d.settlement("x"));
        d.pending("x");d.done("x");d.abort("x");assertEquals("DONE",d.settlement("x"));
        assertThrows(IllegalStateException.class,()->d.done("missing"));
    }
    @Test void wrongListElementTypeDoesNotEraseReceiptLedger() {
        var d=new LifeActivityData();var tag=d.save(new CompoundTag(),null);var strings=new ListTag();strings.add(StringTag.valueOf("bad"));tag.put("Settlements",strings);
        final var malformed=tag;
        assertThrows(IllegalArgumentException.class,()->LifeActivityData.load(malformed,null));
        tag=d.save(new CompoundTag(),null);tag.remove("Started");final var missing=tag;
        assertThrows(IllegalArgumentException.class,()->LifeActivityData.load(missing,null));
    }
    @Test void blankKeysAndNegativeTimesAreRejected() {
        var d=new LifeActivityData();assertThrows(IllegalArgumentException.class,()->d.pending(""));
        assertThrows(IllegalArgumentException.class,()->d.start("sleep",-1));assertThrows(IllegalArgumentException.class,()->d.restart("sleep",-1));
    }
    @Test void unreachablePlaceCooldownPersistsWithoutBlockingOtherPlacesOrActors() {
        var d=new LifeActivityData();var npc=UUID.randomUUID();d.deferPlace(npc,"minecraft:overworld",BlockPos.ZERO,100);
        var loaded=LifeActivityData.load(d.save(new CompoundTag(),null),null);
        assertEquals(700,loaded.placeRetryAt(npc,"minecraft:overworld",BlockPos.ZERO));
        assertEquals(0,loaded.placeRetryAt(npc,"minecraft:overworld",new BlockPos(1,0,0)));
        assertEquals(0,loaded.placeRetryAt(UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO));
        assertEquals(0,loaded.retryAt(npc));
    }
}
