package com.jgeted.sagadyssey.npc.brain.fieldwork;

import net.minecraft.core.BlockPos;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FieldworkCargoTest {
    private final UUID npc = UUID.randomUUID(), plan = UUID.randomUUID();
    @Test void originallyOwnedScaffoldingIsNotNewCargoAfterRecovery() {
        var place = receipt(0, 10, FieldworkAction.Operation.PLACE_SCAFFOLD, "minecraft:dirt#0", -1);
        var recover = receipt(1, 20, FieldworkAction.Operation.RECOVER_SCAFFOLD, "minecraft:dirt#0", 1);
        assertTrue(FieldworkCargo.pending(List.of(place, recover), List.of()).isEmpty());
    }
    @Test void minedMaterialUsedAsScaffoldReturnsToCargoWithoutDuplication() {
        var mine = receipt(0, 5, FieldworkAction.Operation.MINE, "minecraft:cobblestone#0", 2);
        var place = receipt(1, 10, FieldworkAction.Operation.PLACE_SCAFFOLD, "minecraft:cobblestone#0", -1);
        var recover = receipt(2, 20, FieldworkAction.Operation.RECOVER_SCAFFOLD, "minecraft:cobblestone#0", 1);
        assertEquals(Map.of("minecraft:cobblestone#0", 1), FieldworkCargo.pending(List.of(mine, place), List.of()));
        assertEquals(Map.of("minecraft:cobblestone#0", 2), FieldworkCargo.pending(List.of(mine, place, recover), List.of()));
    }
    @Test void externallyLostScaffoldDoesNotGrantInventedRefund() {
        var mine = receipt(0, 5, FieldworkAction.Operation.MINE, "minecraft:cobblestone#0", 1);
        var place = receipt(1, 10, FieldworkAction.Operation.PLACE_SCAFFOLD, "minecraft:cobblestone#0", -1);
        var lost = new FieldworkActionReceipt(action(2, FieldworkAction.Operation.RECOVER_SCAFFOLD), 20, "minecraft:air", List.of(), 0);
        assertTrue(FieldworkCargo.pending(List.of(mine, place, lost), List.of()).isEmpty());
    }
    @Test void replantConsumesOneOfProducedSeedsAndDeliveryReducesExactQuantity() {
        var harvest = receipt(0, 10, FieldworkAction.Operation.HARVEST, "minecraft:wheat_seeds#0", 3);
        var plant = receipt(1, 20, FieldworkAction.Operation.PLANT, "minecraft:wheat_seeds#0", -1);
        var delivery = new FieldworkCargo.Delivery(UUID.randomUUID(), npc, "minecraft:wheat_seeds#0", 2, 30);
        assertEquals(Map.of("minecraft:wheat_seeds#0", 2), FieldworkCargo.pending(List.of(harvest, plant), List.of()));
        assertTrue(FieldworkCargo.pending(List.of(harvest, plant), List.of(delivery)).isEmpty());
    }
    @Test void laterProductionAfterEarlierDeliveryIsNotSwallowedByOldTotals() {
        var a = receipt(0, 10, FieldworkAction.Operation.MINE, "minecraft:coal#0", 1);
        var b = receipt(1, 30, FieldworkAction.Operation.MINE, "minecraft:coal#0", 2);
        var delivery = new FieldworkCargo.Delivery(UUID.randomUUID(), npc, "minecraft:coal#0", 1, 20);
        assertEquals(Map.of("minecraft:coal#0", 2), FieldworkCargo.pending(List.of(b, a), List.of(delivery)));
    }
    @Test void irrigationBucketReplacementIsNotLaborOutput() {
        var action = new FieldworkActionReceipt(action(0, FieldworkAction.Operation.IRRIGATE), 10, "minecraft:water",
                List.of(new FieldworkActionReceipt.ItemDelta("minecraft:water_bucket#0", -1),
                        new FieldworkActionReceipt.ItemDelta("minecraft:bucket#0", 1),
                        new FieldworkActionReceipt.ItemDelta("minecraft:dirt#0", 1)), 0);
        assertEquals(Map.of("minecraft:dirt#0", 1), FieldworkCargo.pending(List.of(action), List.of()));
    }
    @Test void realMealAndRealDeliveryHaveSeparateDebitsAndLeaveOriginalSeedOutOfCargo() {
        var harvest=receipt(0,10,FieldworkAction.Operation.HARVEST,"minecraft:carrot#0",4);
        var plant=receipt(1,20,FieldworkAction.Operation.PLANT,"minecraft:carrot#0",-1);
        var meal=meal("meal",30,1);
        var delivery=new FieldworkCargo.Delivery(UUID.randomUUID(),npc,"minecraft:carrot#0",2,40);
        assertEquals(Map.of("minecraft:carrot#0",2),FieldworkCargo.pending(List.of(harvest,plant),List.of(),List.of(meal)));
        assertTrue(FieldworkCargo.pending(List.of(harvest,plant),List.of(delivery),List.of(meal)).isEmpty(),
                "original twelve seeds were never a production event and are never consumed by this accounting");
    }
    @Test void duplicateConsumptionKeyCannotDebitTwiceButConflictingPayloadIsRejected() {
        var harvest=receipt(0,10,FieldworkAction.Operation.HARVEST,"minecraft:carrot#0",4);
        var meal=meal("meal",30,1);
        assertEquals(Map.of("minecraft:carrot#0",3),FieldworkCargo.pending(List.of(harvest),List.of(),List.of(meal,meal)));
        assertThrows(IllegalStateException.class,()->FieldworkCargo.pending(List.of(harvest),List.of(),List.of(meal,meal("meal",31,1))));
    }
    @Test void foreignOwnedMealWithZeroOutputShareDoesNotConsumeOwnProduction() {
        var harvest=receipt(0,10,FieldworkAction.Operation.HARVEST,"minecraft:carrot#0",3);
        var own=meal("meal",30,0);
        var foreign=new FieldworkCargo.OutputConsumption(own.key(),npc,own.personId(),own.containerId(),
                SubjectRef.household(UUID.randomUUID()),own.fingerprint(),1,0,own.startedAt(),own.committedAt(),5,6,own.state());
        assertEquals(Map.of("minecraft:carrot#0",3),FieldworkCargo.pending(List.of(harvest),List.of(),List.of(foreign)));
        assertThrows(IllegalArgumentException.class,()->new FieldworkCargo.OutputConsumption(own.key(),npc,own.personId(),own.containerId(),
                foreign.owner(),own.fingerprint(),1,1,own.startedAt(),own.committedAt(),5,6,own.state()));
    }
    @Test void pendingConsumptionIsNotSuccessAndLaterCropCannotCoverAnEarlierUnprovenConsumption() {
        var committed=meal("meal",30,1);
        var pending=new FieldworkCargo.OutputConsumption(committed.key(),npc,committed.personId(),committed.containerId(),
                committed.owner(),committed.fingerprint(),1,1,30,-1,5,-1,FieldworkCargo.ConsumptionState.PENDING);
        assertTrue(FieldworkCargo.pending(List.of(),List.of(),List.of(pending)).isEmpty());
        var later=receipt(0,40,FieldworkAction.Operation.HARVEST,"minecraft:carrot#0",4);
        assertThrows(IllegalStateException.class,()->FieldworkCargo.pending(List.of(later),List.of(),List.of(committed)));
    }
    @Test void newProductionAfterConsumedOutputIsStillAvailable() {
        var first=receipt(0,10,FieldworkAction.Operation.HARVEST,"minecraft:carrot#0",1);
        var later=receipt(1,40,FieldworkAction.Operation.HARVEST,"minecraft:carrot#0",4);
        assertEquals(Map.of("minecraft:carrot#0",4),FieldworkCargo.pending(List.of(later,first),List.of(),List.of(meal("meal",30,1))));
    }
    private FieldworkCargo.OutputConsumption meal(String key,long now,int output){
        UUID person=UUID.nameUUIDFromBytes(("person:"+npc).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new FieldworkCargo.OutputConsumption(key,npc,person,ContainerIds.npcPersonal(person),SubjectRef.character(person),
                "minecraft:carrot#0",1,output,now,now,5,6,FieldworkCargo.ConsumptionState.COMMITTED);
    }
    private FieldworkAction action(int step, FieldworkAction.Operation op) {
        return new FieldworkAction(plan, npc, step, "minecraft:overworld", new BlockPos(1, 1, 1), op, "state");
    }
    private FieldworkActionReceipt receipt(int step, long time, FieldworkAction.Operation op, String item, int amount) {
        return new FieldworkActionReceipt(action(step, op), time, "state-after", List.of(new FieldworkActionReceipt.ItemDelta(item, amount)), 0);
    }
}
