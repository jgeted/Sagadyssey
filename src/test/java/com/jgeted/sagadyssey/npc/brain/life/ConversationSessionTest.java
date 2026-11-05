package com.jgeted.sagadyssey.npc.brain.life;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConversationSessionTest {
    private ConversationSession invited() {
        return ConversationSession.invite(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:overworld", new BlockPos(2, 64, 3), 100, 10, 1000);
    }
    @Test void neitherStartsNorFinishesBeforeBothArrive() {
        var waiting = invited().tick(50, false, true).tick(500, false, true);
        assertEquals(ConversationSession.Status.INVITED, waiting.status());
        assertEquals(-1, waiting.startedAt());
    }
    @Test void bothStartTogetherAndFinishAfterRealDuration() {
        var talking = invited().tick(50, true, true);
        assertEquals(50, talking.startedAt());
        assertEquals(ConversationSession.Status.TALKING, talking.tick(149, true, true).status());
        assertEquals(ConversationSession.Status.SUCCEEDED, talking.tick(150, true, true).status());
    }
    @Test void partnerVanishingOrRevokingConsentCancels() {
        var talking = invited().tick(50, true, true);
        assertEquals(ConversationSession.Status.CANCELLED, talking.tick(150, false, true).status());
        assertEquals(ConversationSession.Status.CANCELLED, talking.tick(150, true, false).status());
    }
    @Test void finiteInvitationExpires() {
        assertEquals(ConversationSession.Status.EXPIRED, invited().tick(1000, true, true).status());
    }
    @Test void reloadDoesNotCountOfflineTimeAsSocialInteraction() {
        var talking = invited().tick(50, true, true);
        var restored = ConversationSession.load(talking.save()).recover(500);
        assertEquals(ConversationSession.Status.INVITED, restored.status());
        assertEquals(-1, restored.startedAt());
        assertEquals(500, restored.tick(500, true, true).startedAt());
        assertEquals(ConversationSession.Status.EXPIRED, talking.recover(1000).status());
    }
    @Test void terminalOutcomeIsIdempotentAcrossReload() {
        var finished = invited().tick(50, true, true).tick(150, true, true);
        assertEquals(finished, ConversationSession.load(finished.save()));
        assertSame(finished, finished.tick(500, false, false));
        assertSame(finished, finished.recover(500));
    }
    @Test void malformedCheckpointDoesNotBecomeNewConversation() {
        var tag = invited().save(); tag.remove("Started");
        final var missing = tag;
        assertThrows(IllegalArgumentException.class, () -> ConversationSession.load(missing));
        tag = invited().save(); tag.putInt("Place", 0);
        final var malformed = tag;
        assertThrows(IllegalArgumentException.class, () -> ConversationSession.load(malformed));
    }
    @Test void selfTalkAndBackwardsClockRejected() {
        var a = invited();
        assertThrows(IllegalArgumentException.class, () -> ConversationSession.invite(a.id(), a.first(), a.first(),
                a.dimension(), a.place(), 100, 10, 1000));
        assertThrows(IllegalArgumentException.class, () -> a.tick(9, true, true));
        assertThrows(IllegalArgumentException.class, () -> a.recover(9));
    }
}
