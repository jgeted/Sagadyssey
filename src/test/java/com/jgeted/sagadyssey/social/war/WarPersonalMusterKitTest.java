package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.*;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import com.jgeted.sagadyssey.social.work.ServiceProvision;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WarPersonalMusterKitTest {
    @TempDir Path directory;
    private static Effect effect(com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest r,WarMobilization.Stage stage){return new Effect(r,stage,WarMobilization.operationId(r.id(),stage),WarMobilization.cancellationId(r.id(),stage),Map.of(),100);}
    @Test void zeroLoansHaveDurableEvidenceWithoutInventingAPhysicalTransferAndStillRequireActualEquipment()throws Exception{
        var r=WarMilitiaTestSupport.request();var plan=new WarKitRecord(r,UUID.randomUUID(),ItemLocation.player(r.actorId(),UUID.randomUUID()),List.of(),List.of(),List.of());
        var rights=new RightsStateData();var transfers=new TransferLedgerData();WarMobilization.Receipt receipt;
        try(var physical=new PhysicalItemService(directory.resolve("items.sgw"),rights,transfers)){
            try(var kit=new WarKitAdapter(directory.resolve("kit.sgw"),physical,rights,l->{throw new AssertionError("no borrowed assets, so no endpoint access");},ignored->Optional.empty())){
                long end=System.nanoTime()+5_000_000_000L;while(kit.configure(plan)!=Status.APPLIED&&System.nanoTime()<end)java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                assertTrue(kit.durable(r.id()));var reply=kit.apply(effect(r,WarMobilization.Stage.EQUIP));assertEquals(Status.APPLIED,reply.status());receipt=reply.receipt().orElseThrow();
                assertTrue(kit.confirms(receipt,plan));assertFalse(kit.equipped(r.id()));assertTrue(kit.find(r.id()).orElseThrow().issues().isEmpty());assertFalse(Files.exists(directory.resolve("items.sgw")));
            }
            try(var kit=new WarKitAdapter(directory.resolve("kit.sgw"),physical,rights,l->{throw new AssertionError();},ignored->Optional.empty())){
                assertEquals(receipt,kit.lookup(effect(r,WarMobilization.Stage.EQUIP)).receipt().orElseThrow());var returned=kit.apply(effect(r,WarMobilization.Stage.RETURN_EQUIPMENT));assertEquals(Status.APPLIED,returned.status());assertTrue(kit.confirms(returned.receipt().orElseThrow(),plan));
            }
        }
    }
    @Test void playerSourceCannotBeChangedToAnotherCharacterThroughAnItemLocation()throws Exception{
        var r=WarMilitiaTestSupport.request();var player=ItemLocation.player(r.actorId(),UUID.randomUUID());assertEquals(player,ItemLocation.load(player.save()));
        assertThrows(IllegalArgumentException.class,()->new WarKitRecord(r,UUID.randomUUID(),ItemLocation.player(UUID.randomUUID(),player.owner()),List.of(),List.of(),List.of()));
        var forged=player.save();forged.store("Character",net.minecraft.core.UUIDUtil.CODEC,UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->ItemLocation.load(forged));
    }
    @Test void payrollTermsBindThePayerToTheCurrentLifeInventoryRatherThanAnArbitraryAccount(){
        var r=WarMilitiaTestSupport.request();var player=ItemLocation.player(r.actorId(),UUID.randomUUID());var wage=new ItemFingerprint("minecraft:emerald",0);
        assertEquals(player,new ServiceProvision.Terms(r.id(),"sagadyssey:war",r.armyId(),r.characterId(),UUID.randomUUID(),SubjectRef.character(r.actorId()),player,wage,1,24000).treasury());
        assertThrows(IllegalArgumentException.class,()->new ServiceProvision.Terms(r.id(),"sagadyssey:war",r.armyId(),r.characterId(),UUID.randomUUID(),SubjectRef.character(UUID.randomUUID()),player,wage,1,24000));
    }
    @Test void zeroLoanReceiptUsesItsDurablePredecessorTimeSoItCannotMoveTheSequenceBackwards()throws Exception{
        var r=WarMilitiaTestSupport.request();var p=new WarKitRecord(r,UUID.randomUUID(),ItemLocation.player(r.actorId(),UUID.randomUUID()),List.of(),List.of(),List.of());
        try(var physical=new PhysicalItemService(directory.resolve("physical.sgw"),new RightsStateData(),new TransferLedgerData());var kit=new WarKitAdapter(directory.resolve("kit.sgw"),physical,new RightsStateData(),ignored->Optional.empty(),ignored->Optional.empty())){
            long end=System.nanoTime()+5_000_000_000L;while(kit.configure(p)!=Status.APPLIED&&System.nanoTime()<end)java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            var stage=WarMobilization.Stage.SUSTAIN;var prior=new WarMobilization.Receipt(r.id(),r.characterId(),stage,WarMobilization.operationId(r.id(),stage),r.id(),99,false);
            var equip=effect(r,WarMobilization.Stage.EQUIP);var e=new Effect(r,equip.stage(),equip.operationId(),equip.cancellationId(),Map.of(stage,prior),100);
            var receipt=kit.lookup(e).receipt().orElseThrow();assertEquals(99,receipt.recordedAtTick());assertEquals(receipt,kit.lookup(new Effect(r,e.stage(),e.operationId(),e.cancellationId(),e.priorReceipts(),200)).receipt().orElseThrow());
        }
    }
}
