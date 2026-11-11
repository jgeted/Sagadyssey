package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NpcCommandDataTest {
    private NpcCommandIntent intent(UUID person){return new NpcCommandIntent(UUID.randomUUID(),person,UUID.randomUUID(),UUID.randomUUID(),
            List.of(UUID.randomUUID()),NpcCommand.PATROL,NpcCommand.WORK,"minecraft:overworld",new BlockPos(10,64,20),8,100,1100,NpcCommandIntent.Status.QUEUED,0,100,"ordinary queue");}
    @Test void strictRoundtripKeepsIssuerEvidenceScopeAndReceipt(){var d=new NpcCommandData();var i=intent(UUID.randomUUID());d.put(i.update(NpcCommandIntent.Status.ACTIVE,3,110,"three legs"));var loaded=NpcCommandData.load(d.save(new CompoundTag(),null),null);assertEquals(d.all(),loaded.all());assertEquals(3,loaded.active(i.character()).orElseThrow().leg());}
    @Test void onePendingAndOneActivePerCharacter(){var d=new NpcCommandData();UUID person=UUID.randomUUID();var i=intent(person);d.put(i);assertThrows(IllegalStateException.class,()->d.put(intent(person)));d.put(i.update(NpcCommandIntent.Status.ACTIVE,0,101,"active"));var next=intent(person);d.put(next);assertEquals(i.id(),d.active(person).orElseThrow().id());assertEquals(next.id(),d.queued(person).orElseThrow().id());}
    @Test void terminalRemovesIndexedSlotButPreservesAudit(){var d=new NpcCommandData();var i=intent(UUID.randomUUID());d.put(i);var closed=i.update(NpcCommandIntent.Status.CANCELLED,0,102,"cancelled");d.put(closed);assertTrue(d.queued(i.character()).isEmpty());assertEquals(closed,d.find(i.id()));assertThrows(IllegalStateException.class,()->d.put(i));}
    @Test void expiredIntentCannotBeRevived(){var i=intent(UUID.randomUUID()).update(NpcCommandIntent.Status.EXPIRED,0,1100,"expired");assertThrows(IllegalStateException.class,()->i.update(NpcCommandIntent.Status.ACTIVE,0,1101,"revive"));}
    @Test void authorityIdentityCannotBeRebound(){var d=new NpcCommandData();var i=intent(UUID.randomUUID());d.put(i);var replacement=new NpcCommandIntent(i.id(),i.character(),UUID.randomUUID(),i.player(),i.evidence(),i.command(),i.previous(),i.dimension(),i.anchor(),i.radius(),i.started(),i.expires(),i.status(),0,101,"");assertThrows(IllegalStateException.class,()->d.put(replacement));}
    @Test void activeCannotMoveBackToQueue(){var i=intent(UUID.randomUUID()).update(NpcCommandIntent.Status.ACTIVE,1,110,"");assertThrows(IllegalStateException.class,()->i.update(NpcCommandIntent.Status.QUEUED,1,111,""));}
    @Test void progressAndTimeCannotMoveBackwards(){var i=intent(UUID.randomUUID()).update(NpcCommandIntent.Status.ACTIVE,2,110,"");assertThrows(IllegalStateException.class,()->i.update(NpcCommandIntent.Status.ACTIVE,1,111,""));assertThrows(IllegalStateException.class,()->i.update(NpcCommandIntent.Status.ACTIVE,2,109,""));}
    @Test void absentAuthorityEvidenceRejected(){var t=intent(UUID.randomUUID()).save();t.put("Evidence",new ListTag());assertThrows(IllegalArgumentException.class,()->NpcCommandIntent.load(t));}
    @Test void noImplicitUnlimitedLifetime(){var t=intent(UUID.randomUUID()).save();t.putLong("Expires",24101);assertThrows(IllegalArgumentException.class,()->NpcCommandIntent.load(t));t.putLong("Expires",100);assertThrows(IllegalArgumentException.class,()->NpcCommandIntent.load(t));}
    @Test void unknownSchemaAndMissingCriticalFieldsFailClosed(){var d=new NpcCommandData();var t=d.save(new CompoundTag(),null);t.putInt("Schema",2);assertThrows(IllegalArgumentException.class,()->NpcCommandData.load(t,null));var i=intent(UUID.randomUUID()).save();i.remove("Started");assertThrows(IllegalArgumentException.class,()->NpcCommandIntent.load(i));}
    @Test void duplicateSavedIdsFailClosed(){var d=new NpcCommandData();d.put(intent(UUID.randomUUID()));var t=d.save(new CompoundTag(),null);var list=MinecraftApi.listOfType(t, "Intents",Tag.TAG_COMPOUND);list.add(list.get(0).copy());assertThrows(IllegalArgumentException.class,()->NpcCommandData.load(t,null));}
    @Test void commandNeverGrantsEquipmentOrPrivateInventoryScope(){assertNull(NpcCommandIntent.scope(NpcCommand.IDLE));assertEquals(com.jgeted.sagadyssey.social.service.AuthorityScope.WORK,NpcCommandIntent.scope(NpcCommand.WORK));assertEquals(com.jgeted.sagadyssey.social.service.AuthorityScope.PATROL,NpcCommandIntent.scope(NpcCommand.PATROL));}
    @Test void ordinaryProjectionContainsOnlyThisIssuersRequestsForThisPerson(){var d=new NpcCommandData();var i=intent(UUID.randomUUID());var other=intent(UUID.randomUUID());d.put(i);d.put(other);assertEquals(List.of(i),d.known(i.issuer(),i.character()));assertTrue(d.known(other.issuer(),i.character()).isEmpty());assertTrue(d.known(i.issuer(),other.character()).isEmpty());assertThrows(UnsupportedOperationException.class,()->d.known(i.issuer(),i.character()).clear());}
}
