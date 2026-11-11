package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FieldworkDataTest {
    @Test
    void saveReloadResumesScanAtExactCursorRatherThanNpcNearbyOrigin() {
        UUID npc = UUID.randomUUID();
        var area = new FieldworkSearchArea("minecraft:overworld", new BlockPos(40, -60, 0), new BlockPos(80, -60, 0));
        FieldworkData data = new FieldworkData();
        var cursor = FieldworkScan.advance(data.scanFor(npc, area), 16, 10L, 610L,
                position -> FieldworkScan.ProbeResult.NONE).cursor();
        data.saveScan(npc, cursor);
        FieldworkData loaded = reload(data);
        assertEquals(cursor, loaded.scanFor(npc, area));
        var result = FieldworkScan.advance(loaded.scanFor(npc, area), 16, 20L, 620L, position -> {
            assertEquals(new BlockPos(56, -60, 0), position);
            return FieldworkScan.ProbeResult.AVAILABLE;
        });
        assertEquals(FieldworkScan.Status.FOUND, result.status());
        assertEquals(17L, result.cursor().nextIndex());
    }

    @Test
    void changingWorkZoneResetsCursorButPreservesReceiptsAndOtherNpcScans() {
        FieldworkData data = new FieldworkData();
        UUID npc = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var original = new FieldworkSearchArea("minecraft:overworld", BlockPos.ZERO, new BlockPos(4, 0, 0));
        var changed = new FieldworkSearchArea("minecraft:overworld", new BlockPos(80, 0, 0), new BlockPos(90, 0, 0));
        data.saveScan(npc, new FieldworkScan.Cursor(original, 3L, true, 600L));
        data.saveScan(other, new FieldworkScan.Cursor(original, 2L, false, 0L));
        var action = FieldworkCommitServiceTest.action(FieldworkAction.Operation.MINE);
        var receipt = FieldworkCommitServiceTest.receipt(action, 10L);
        data.record(receipt);
        assertEquals(FieldworkScan.Cursor.begin(changed), data.scanFor(npc, changed));
        assertEquals(2L, data.scan(other).orElseThrow().nextIndex());
        assertEquals(receipt, data.receipt(action.key()).orElseThrow());
    }

    @Test
    void unreachableMemorySurvivesReloadExpiresAtBoundaryAndIsScopedByNpcAndDimension() {
        FieldworkData data = new FieldworkData();
        UUID npc = UUID.randomUUID();
        BlockPos pos = new BlockPos(80, -60, 0);
        data.rememberUnreachable(npc, "minecraft:overworld", pos, 10L, 610L, "路径暂时不通");
        FieldworkData loaded = reload(data);
        assertTrue(loaded.unreachable(npc, "minecraft:overworld", pos, 609L).isPresent());
        assertTrue(loaded.unreachable(npc, "minecraft:overworld", pos, 610L).isEmpty());
        assertTrue(loaded.unreachable(npc, "minecraft:the_nether", pos, 609L).isEmpty());
        assertTrue(loaded.unreachable(UUID.randomUUID(), "minecraft:overworld", pos, 609L).isEmpty());
        assertEquals(1, loaded.memories(npc).size(), "read-only audit keeps expired evidence");
    }

    @Test
    void unreachableMemoryIsBoundedAndDoesNotLeavePermanentBlacklist() {
        FieldworkData data = new FieldworkData();
        UUID npc = UUID.randomUUID();
        for (int x = 0; x < 300; x++) {
            data.rememberUnreachable(npc, "minecraft:overworld", new BlockPos(x, 0, 0), 10L, 100L + x, "暂缓");
        }
        assertEquals(256, data.memories(npc).size());
        assertTrue(data.unreachable(npc, "minecraft:overworld", BlockPos.ZERO, 10L).isEmpty());
        data.rememberUnreachable(npc, "minecraft:overworld", new BlockPos(500, 0, 0), 400L, 1_000L, "新路径");
        assertEquals(1, data.memories(npc).size());
        assertThrows(IllegalArgumentException.class, () -> data.rememberUnreachable(npc,
                "minecraft:overworld", BlockPos.ZERO, 10L, 10L, "必须有未来期限"));
    }

    @Test
    void receiptsPreserveSeedConsumptionOutputToolDamageAndSeparateScaffoldSteps() {
        FieldworkData data = new FieldworkData();
        var base = FieldworkCommitServiceTest.action(FieldworkAction.Operation.PLANT);
        var planted = new FieldworkActionReceipt(base, 10L, "minecraft:wheat[age=0]",
                List.of(new FieldworkActionReceipt.ItemDelta("minecraft:wheat_seeds", -1)), 0);
        data.record(planted);
        var placed = new FieldworkAction(base.planId(), base.npcId(), 3, base.dimension(), base.target(),
                FieldworkAction.Operation.PLACE_SCAFFOLD, "minecraft:air");
        var recovered = new FieldworkAction(base.planId(), base.npcId(), 4, base.dimension(), base.target(),
                FieldworkAction.Operation.RECOVER_SCAFFOLD, "minecraft:cobblestone");
        data.record(new FieldworkActionReceipt(placed, 20L, "minecraft:cobblestone",
                List.of(new FieldworkActionReceipt.ItemDelta("minecraft:cobblestone", -1)), 0));
        data.record(new FieldworkActionReceipt(recovered, 30L, "minecraft:air",
                List.of(new FieldworkActionReceipt.ItemDelta("minecraft:cobblestone", 1)), 1));
        FieldworkData loaded = reload(data);
        assertEquals(planted, loaded.receipt(base.key()).orElseThrow());
        assertEquals(3, loaded.receiptsFor(base.npcId()).size());
        assertEquals(0, loaded.receiptsFor(UUID.randomUUID()).size());
        assertEquals(0, loaded.receiptsFor(base.npcId()).stream().flatMap(value -> value.inventoryDeltas().stream())
                .filter(value -> value.fingerprint().equals("minecraft:cobblestone"))
                .mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum());
    }

    @Test
    void duplicateReceiptIsIdempotentButConflictingReceiptIsNotOverwritten() {
        FieldworkData data = new FieldworkData();
        var action = FieldworkCommitServiceTest.action(FieldworkAction.Operation.CHOP);
        var receipt = FieldworkCommitServiceTest.receipt(action, 10L);
        data.record(receipt);
        data.record(receipt);
        assertEquals(1, data.receiptsFor(action.npcId()).size());
        assertThrows(IllegalStateException.class, () -> data.record(FieldworkCommitServiceTest.receipt(action, 20L)));
        assertEquals(receipt, data.receipt(action.key()).orElseThrow());
    }

    @Test
    void corruptReceiptFailsClosedRatherThanAllowingDuplicateHarvest() {
        FieldworkData data = new FieldworkData();
        var action = FieldworkCommitServiceTest.action(FieldworkAction.Operation.HARVEST);
        data.record(FieldworkCommitServiceTest.receipt(action, 10L));
        CompoundTag tag = data.save(new CompoundTag(), null);
        MinecraftApi.listOfType(tag, "Receipts", Tag.TAG_COMPOUND).getCompoundOrEmpty(0).remove("ToolDamage");
        assertThrows(IllegalStateException.class, () -> FieldworkData.load(tag, null));
        CompoundTag another = data.save(new CompoundTag(), null);
        another.put("Receipts", new ListTag());
        another.putInt("Schema", 999);
        assertThrows(IllegalStateException.class, () -> FieldworkData.load(another, null));
    }

    @Test
    void conflictingDuplicateInSaveIsRejectedRatherThanLastWriterWinning() {
        FieldworkData data = new FieldworkData();
        var action = FieldworkCommitServiceTest.action(FieldworkAction.Operation.MINE);
        data.record(FieldworkCommitServiceTest.receipt(action, 10L));
        CompoundTag tag = data.save(new CompoundTag(), null);
        ListTag receipts = MinecraftApi.listOfType(tag, "Receipts", Tag.TAG_COMPOUND);
        CompoundTag conflicting = receipts.getCompoundOrEmpty(0).copy();
        conflicting.putLong("CommittedAt", 20L);
        receipts.add(conflicting);
        assertThrows(IllegalStateException.class, () -> FieldworkData.load(tag, null));
    }

    @Test
    void badCursorCanRestartWithoutDroppingValidCommittedReceipt() {
        FieldworkData data = new FieldworkData();
        UUID npc = UUID.randomUUID();
        var area = new FieldworkSearchArea("minecraft:overworld", BlockPos.ZERO, new BlockPos(2, 0, 0));
        data.scanFor(npc, area);
        var action = FieldworkCommitServiceTest.action(FieldworkAction.Operation.MINE);
        var receipt = FieldworkCommitServiceTest.receipt(action, 10L);
        data.record(receipt);
        CompoundTag tag = data.save(new CompoundTag(), null);
        MinecraftApi.listOfType(tag, "Scans", Tag.TAG_COMPOUND).getCompoundOrEmpty(0).putLong("NextIndex", 1_000L);
        FieldworkData loaded = FieldworkData.load(tag, null);
        assertTrue(loaded.scan(npc).isEmpty());
        assertEquals(FieldworkScan.Cursor.begin(area), loaded.scanFor(npc, area));
        assertEquals(receipt, loaded.receipt(action.key()).orElseThrow());
    }

    @Test
    void checkpointRoundTripIsDeterministicAndRejectsPackedPositionTruncation() {
        FieldworkData data = new FieldworkData();
        UUID npc = UUID.randomUUID();
        var area = new FieldworkSearchArea("minecraft:overworld", BlockPos.ZERO, new BlockPos(80, 0, 0));
        data.saveScan(npc, new FieldworkScan.Cursor(area, 40L, true, 600L));
        data.rememberUnreachable(npc, "minecraft:overworld", new BlockPos(80, 0, 0), 10L, 610L, "路径");
        var action = FieldworkCommitServiceTest.action(FieldworkAction.Operation.MINE);
        data.record(FieldworkCommitServiceTest.receipt(action, 10L));
        CompoundTag tag = data.save(new CompoundTag(), null);
        assertEquals(tag, reload(data).save(new CompoundTag(), null));
        assertThrows(IllegalArgumentException.class, () -> new FieldworkAction(action.planId(), npc, 2,
                "minecraft:overworld", new BlockPos(0, 4_096, 0), action.operation(), "minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> data.rememberUnreachable(npc, "minecraft:overworld",
                new BlockPos(Integer.MAX_VALUE, 0, 0), 10L, 610L, "越界"));
    }

    private static FieldworkData reload(FieldworkData data) {
        return FieldworkData.load(data.save(new CompoundTag(), null), null);
    }

    @Test
    void storageDeserializerFailureCannotFallBackToEmptyLedgerOverExistingFile() {
        assertThrows(IllegalStateException.class, () -> FieldworkData.resolveLoaded(null, false));
        assertNotNull(FieldworkData.resolveLoaded(null, true));
        FieldworkData valid = new FieldworkData();
        assertSame(valid, FieldworkData.resolveLoaded(valid, false));
        assertSame(valid, FieldworkData.resolveLoaded(valid, true));
    }

    @Test
    void loadingValidCheckpointDoesNotMarkItDirtyUntilRealUpdate() {
        FieldworkData data = new FieldworkData();
        UUID npc = UUID.randomUUID();
        data.rememberUnreachable(npc, "minecraft:overworld", BlockPos.ZERO, 10L, 610L, "路径");
        var action = FieldworkCommitServiceTest.action(FieldworkAction.Operation.MINE);
        var receipt = FieldworkCommitServiceTest.receipt(action, 10L);
        data.record(receipt);
        FieldworkData loaded = reload(data);
        assertFalse(loaded.isDirty());
        loaded.receipt(action.key());
        loaded.memories(npc);
        loaded.record(receipt);
        assertFalse(loaded.isDirty());
        loaded.rememberUnreachable(npc, "minecraft:overworld", new BlockPos(1, 0, 0), 20L, 620L, "新路径");
        assertTrue(loaded.isDirty());
    }

    @Test void outputConsumptionAndProductionSurviveSchemaTwoReloadWithoutBecomingDelivery() {
        var data=producedCarrots();var intent=consumptionIntent(data,"meal-one");
        assertTrue(data.beginOutputConsumption(intent));
        assertFalse(data.beginOutputConsumption(intent),"same pending key cannot authorize another physical consumption");
        var pending=reload(data);assertTrue(pending.hasUnresolvedOutputConsumption(intent.npcId()));
        assertEquals(3,pending.pendingCargo(intent.npcId()).get("minecraft:carrot#0"));
        assertFalse(pending.outputConsumptionReview(intent.npcId(),key->"DONE").isEmpty(),"pending versus life DONE remains ambiguous");
        data.commitOutputConsumption(intent.key(),1,6,31);
        data.commitOutputConsumption(intent.key(),1,6,31);
        var committed=reload(data);
        assertEquals(2,committed.pendingCargo(intent.npcId()).get("minecraft:carrot#0"));
        assertEquals(1,committed.outputConsumptions(intent.npcId()).size());
        assertEquals("",committed.outputConsumptionReview(intent.npcId(),key->"DONE"));
        assertFalse(committed.outputConsumptionReview(intent.npcId(),key->"PENDING").isEmpty());
        var tag=committed.save(new CompoundTag(),null);
        assertEquals(2,tag.getIntOr("Schema", 0));assertEquals(0,MinecraftApi.listOfType(tag, "Deliveries",Tag.TAG_COMPOUND).size());
        assertEquals(tag,committed.save(new CompoundTag(),null));
    }
    @Test void conflictsAndUnprovenCommitCannotOverwriteConsumptionEvidence() {
        var data=producedCarrots();var intent=consumptionIntent(data,"meal-one");data.beginOutputConsumption(intent);
        var conflict=new FieldworkCargo.OutputConsumption(intent.key(),intent.npcId(),intent.personId(),intent.containerId(),intent.owner(),
                intent.fingerprint(),2,2,30,-1,5,-1,FieldworkCargo.ConsumptionState.PENDING);
        assertThrows(IllegalStateException.class,()->data.beginOutputConsumption(conflict));
        assertThrows(IllegalStateException.class,()->data.commitOutputConsumption(intent.key(),2,6,31));
        assertThrows(IllegalArgumentException.class,()->data.commitOutputConsumption(intent.key(),1,7,31));
        assertEquals(intent,data.outputConsumption(intent.key()).orElseThrow());
        data.commitOutputConsumption(intent.key(),1,6,31);
        assertThrows(IllegalStateException.class,()->data.commitOutputConsumption(intent.key(),1,6,32));
        assertThrows(IllegalStateException.class,()->data.abortOutputConsumption(intent.key()));
        assertEquals(2,data.pendingCargo(intent.npcId()).get("minecraft:carrot#0"));
    }
    @Test void failedConsumptionCanAbortOnlyPendingAndDoesNotDebitProduction() {
        var data=producedCarrots();var intent=consumptionIntent(data,"meal-one");data.beginOutputConsumption(intent);
        data.abortOutputConsumption(intent.key());
        assertTrue(data.outputConsumption(intent.key()).isEmpty());
        assertEquals(3,reload(data).pendingCargo(intent.npcId()).get("minecraft:carrot#0"));
    }
    @Test void unrelatedCommitBetweenIntentAndConsumptionCannotSpendSameOutputTwice() {
        var data=producedCarrots();var intent=consumptionIntent(data,"meal-one");data.beginOutputConsumption(intent);
        data.delivered(new FieldworkCargo.Delivery(UUID.randomUUID(),intent.npcId(),"minecraft:carrot#0",3,31));
        assertThrows(IllegalStateException.class,()->data.commitOutputConsumption(intent.key(),1,6,32));
        assertEquals(FieldworkCargo.ConsumptionState.PENDING,data.outputConsumption(intent.key()).orElseThrow().state());
        assertFalse(data.outputConsumptionReview(intent.npcId(),key->"PENDING").isEmpty());
    }
    @Test void schemaOneReadsWithoutInventingConsumptionAndWritesExplicitSchemaTwo() {
        var data=producedCarrots();var intent=consumptionIntent(data,"meal-one");
        data.delivered(new FieldworkCargo.Delivery(UUID.randomUUID(),intent.npcId(),"minecraft:carrot#0",2,40));
        var old=data.save(new CompoundTag(),null);old.putInt("Schema",1);old.remove("OutputConsumptions");
        var loaded=FieldworkData.load(old,null);
        assertTrue(loaded.outputConsumptions(intent.npcId()).isEmpty());
        assertEquals(1,loaded.pendingCargo(intent.npcId()).get("minecraft:carrot#0"),"legacy unexplained tail is not silently repaired");
        assertEquals(2,loaded.save(new CompoundTag(),null).getIntOr("Schema", 0));
        assertFalse(loaded.isDirty(),"compatibility read itself is not a world migration write");
    }
    @Test void missingCorruptDuplicateOrDowngradedConsumptionListIsRejectedWithoutEmptyFallback() {
        var data=producedCarrots();var intent=consumptionIntent(data,"meal-one");data.beginOutputConsumption(intent);
        var missing=data.save(new CompoundTag(),null);missing.remove("OutputConsumptions");
        assertThrows(IllegalStateException.class,()->FieldworkData.load(missing,null));
        var duplicate=data.save(new CompoundTag(),null);var rows=MinecraftApi.listOfType(duplicate, "OutputConsumptions",Tag.TAG_COMPOUND);rows.add(rows.getCompoundOrEmpty(0).copy());
        assertThrows(IllegalStateException.class,()->FieldworkData.load(duplicate,null));
        var invalid=data.save(new CompoundTag(),null);MinecraftApi.listOfType(invalid, "OutputConsumptions",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).putInt("OutputQuantity",2);
        assertThrows(IllegalStateException.class,()->FieldworkData.load(invalid,null));
        var downgrade=data.save(new CompoundTag(),null);downgrade.putInt("Schema",1);
        assertThrows(IllegalStateException.class,()->FieldworkData.load(downgrade,null));
        assertThrows(IllegalStateException.class,()->FieldworkData.resolveLoaded(null,false));
    }
    @Test void actualServiceSlotAndOriginalOwnerDetermineOutputShareEvenWithMixedIdenticalFood() throws Exception {
        for(boolean reserveForeign:List.of(false,true)){
            var data=producedCarrots();var template=consumptionIntent(data,"mixed-meal");
            var own=SubjectRef.character(template.personId());var foreign=SubjectRef.household(UUID.randomUUID());
            var rights=new com.jgeted.sagadyssey.worldsim.rights.RightsStateData();
            var bag=new net.minecraft.world.SimpleContainer(2);
            bag.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARROT,3));
            bag.setItem(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARROT,15));
            var item=com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint.of(bag.getItem(0));var id=template.containerId();
            rights.ensureContext(id,"mixed original bag",own,own,com.jgeted.sagadyssey.worldsim.rights.AccessPolicy.PRIVATE);
            rights.putClaim(new com.jgeted.sagadyssey.worldsim.rights.AssetClaim(UUID.randomUUID(),id,0,item,3,foreign,own,
                    java.util.Set.of(),java.util.Set.of(),"lawful external food",0));
            rights.grant(new com.jgeted.sagadyssey.worldsim.rights.RightGrant(UUID.randomUUID(),id,own,
                    java.util.Set.of(com.jgeted.sagadyssey.worldsim.rights.AssetRight.CONSUME),"declared lawful initial consumption grant",0,null,true));
            if(reserveForeign)assertTrue(rights.reserve(new com.jgeted.sagadyssey.worldsim.rights.AssetReservation(UUID.randomUUID(),id,item,3,
                    foreign,"another-obligation","reserved external batch",0,100),18,0));
            var executor=com.jgeted.sagadyssey.npc.brain.life.LifeTaskExecutor.class;
            var predict=executor.getDeclaredMethod("predictOneConsumption",com.jgeted.sagadyssey.worldsim.rights.RightsStateData.class,
                    net.minecraft.world.Container.class,UUID.class,SubjectRef.class,com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint.class,String.class,long.class);
            predict.setAccessible(true);var prediction=predict.invoke(null,rights,bag,id,own,item,template.key(),30L);assertNotNull(prediction);
            var ownerMethod=prediction.getClass().getDeclaredMethod("owner");ownerMethod.setAccessible(true);
            var actualOwner=(SubjectRef)ownerMethod.invoke(prediction);
            assertEquals(reserveForeign?own:foreign,actualOwner);
            var intent=new FieldworkCargo.OutputConsumption(template.key(),template.npcId(),template.personId(),id,actualOwner,
                    "minecraft:carrot#0",1,actualOwner.equals(own)?1:0,30,-1,rights.context(id).revision(),-1,FieldworkCargo.ConsumptionState.PENDING);
            data.beginOutputConsumption(intent);
            var result=new com.jgeted.sagadyssey.worldsim.transfer.ConsumptionService(rights).consume(id,bag,own,item,1,template.key(),31);
            assertTrue(result.successful());
            var verify=executor.getDeclaredMethod("matchesActualConsumption",prediction.getClass(),net.minecraft.world.Container.class,
                    com.jgeted.sagadyssey.worldsim.rights.RightsStateData.class,UUID.class,
                    com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint.class,long.class);
            verify.setAccessible(true);assertEquals(true,verify.invoke(null,prediction,bag,rights,id,item,result.finalRevision()));
            assertEquals(reserveForeign?3:2,bag.getItem(0).getCount());assertEquals(reserveForeign?14:15,bag.getItem(1).getCount());
            data.commitOutputConsumption(intent.key(),result.consumed(),result.finalRevision(),31);
            assertEquals(reserveForeign?2:3,reload(data).pendingCargo(intent.npcId()).get("minecraft:carrot#0"));
            assertEquals(1,data.outputConsumption(intent.key()).orElseThrow().quantity());
            assertEquals(reserveForeign?1:0,data.outputConsumption(intent.key()).orElseThrow().outputQuantity());
            bag.getItem(1).shrink(1);
            assertEquals(false,verify.invoke(null,prediction,bag,rights,id,item,result.finalRevision()),"extra unexplained shrink must remain uncommitted");
        }
    }
    private static FieldworkData producedCarrots(){
        var data=new FieldworkData();UUID body=UUID.randomUUID();
        var action=new FieldworkAction(UUID.randomUUID(),body,2,"minecraft:overworld",BlockPos.ZERO,FieldworkAction.Operation.HARVEST,"minecraft:carrots");
        data.record(new FieldworkActionReceipt(action,10,"minecraft:air",List.of(new FieldworkActionReceipt.ItemDelta("minecraft:carrot#0",3)),0));
        return data;
    }
    private static FieldworkCargo.OutputConsumption consumptionIntent(FieldworkData data,String key){
        var tag=data.save(new CompoundTag(),null);UUID body=MinecraftApi.listOfType(tag, "Receipts",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).read("NpcId", UUIDUtil.CODEC).orElseThrow(),person=UUID.randomUUID();
        return new FieldworkCargo.OutputConsumption(key,body,person,ContainerIds.npcPersonal(person),SubjectRef.character(person),
                "minecraft:carrot#0",1,1,30,-1,5,-1,FieldworkCargo.ConsumptionState.PENDING);
    }
}
