package com.jgeted.sagadyssey.social.war;
import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarCampaignEntry;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarCampaignEntry.*;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarCampaignCodec;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarCampaignJournalTest {
    @TempDir Path root;
    static ItemLocation block(){return ItemLocation.block(UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO);}
    static Shipment shipment(){return new Shipment(WarCampaignRulesTest.run(2),block(),block(),new ItemFingerprint("minecraft:bread",0),Optional.empty());}
    @Test void roundTripPreservesExactCargoAndTermReferences()throws Exception{var s=shipment();var d=new Depot(s.run().army(),s.run().owner(),s.source(),1);var codec=new WarCampaignCodec();assertEquals(Set.of(s,d),Set.copyOf(codec.read(codec.write(List.of(s,d)))));}
    @Test void actualReopeningRetainsResponsibilityAndCorruptionDoesNotRepairItself()throws Exception{
        var p=root.resolve("campaign.sgw");var s=shipment();try(var store=new DurableSnapshotStore<>(p,new WarCampaignCodec(),WarCampaignEntry::id,WarCampaignEntry::follows,"test")){store.persist(s);}
        try(var store=new DurableSnapshotStore<>(p,new WarCampaignCodec(),WarCampaignEntry::id,WarCampaignEntry::follows,"test")){assertEquals(List.of(s),List.copyOf(store.recover()));}
        var bytes=Files.readAllBytes(p);bytes[bytes.length-1]^=1;Files.write(p,bytes);assertThrows(Exception.class,()->new DurableSnapshotStore<>(p,new WarCampaignCodec(),WarCampaignEntry::id,WarCampaignEntry::follows,"test"));assertArrayEquals(bytes,Files.readAllBytes(p));
    }
    @Test void aMissingPrimaryWithBackupCannotDiscardOriginalCargo()throws Exception{var p=root.resolve("campaign.sgw");Files.write(p.resolveSibling("campaign.sgw.old"),new byte[]{1});assertThrows(Exception.class,()->new DurableSnapshotStore<>(p,new WarCampaignCodec(),WarCampaignEntry::id,WarCampaignEntry::follows,"test"));assertFalse(Files.exists(p));}
    @Test void twoSimultaneousCarrierClaimsFailInsteadOfDuplicatingLabor(){var s=shipment();var r=s.run();var other=new WarSupplyRun(UUID.randomUUID(),r.army(),r.owner(),r.service(),r.person(),r.body(),1,1,r.createdAt(),r.deadline(),r.phase(),0,0,1,r.changedAt(),false,"other");assertThrows(Exception.class,()->new WarCampaignCodec().write(List.of(s,new Shipment(other,s.source(),s.target(),s.item(),Optional.empty()))));}
    @Test void aForeignPendingTransferCannotHideInsideACampaignRecord(){var s=shipment();var r=s.run().advance(WarSupplyRun.Phase.LOADING,11,"arrived").attempted(12,"intent");var op=UUID.randomUUID();var intent=PhysicalItemOperation.Intent.transfer(new TransferRequest(op,SubjectRef.character(r.owner()),TransferIntent.ISSUE,List.of(new TransferLeg(s.source().id(),ContainerIds.npcCargo(r.person()),s.item(),r.quantity())),List.of(new ExpectedRevision(s.source().id(),0),new ExpectedRevision(ContainerIds.npcCargo(r.person()),0)),"",12));assertThrows(Exception.class,()->new WarCampaignCodec().write(List.of(new Shipment(r,s.source(),s.target(),s.item(),Optional.of(intent)))));}
    @Test void aCorrectLoadingIntentSurvivesReopeningWithoutBeingAnAppliedReceipt()throws Exception{
        var s=shipment();var r=s.run().advance(WarSupplyRun.Phase.LOADING,11,"arrived").attempted(12,"intent");var op=WarCampaignIdentity.operation("shipment/"+r.id()+"/0");var intent=PhysicalItemOperation.Intent.transfer(new TransferRequest(op,SubjectRef.character(r.owner()),TransferIntent.ISSUE,List.of(new TransferLeg(s.source().id(),ContainerIds.npcCargo(r.person()),s.item(),r.quantity())),List.of(new ExpectedRevision(s.source().id(),0),new ExpectedRevision(ContainerIds.npcCargo(r.person()),0)),WarCampaignIdentity.cargo(r.service(),r.id()),12));var value=new Shipment(r,s.source(),s.target(),s.item(),Optional.of(intent));var codec=new WarCampaignCodec();var bytes=codec.write(List.of(value));var restored=(Shipment)codec.read(bytes).iterator().next();assertEquals(r,restored.run());assertEquals(value.source(),restored.source());assertEquals(value.target(),restored.target());assertEquals(intent.identity(),restored.pending().orElseThrow().identity());assertArrayEquals(bytes,codec.write(List.of(restored)));
    }
}
