package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ForestryReceiptCompatibilityTest {
    private final UUID body=UUID.randomUUID();
    private FieldworkActionReceipt action(FieldworkAction.Operation op,int q,long now) {
        var a=new FieldworkAction(UUID.randomUUID(),body,0,"minecraft:overworld",BlockPos.ZERO,op,"old-state");
        return new FieldworkActionReceipt(a,now,"result-state",List.of(new FieldworkActionReceipt.ItemDelta("minecraft:oak_sapling#0",q)),0);
    }
    @Test void existingPlantInputConsumesProducedSeedWithoutSecondLedger() {
        var cut=action(FieldworkAction.Operation.CHOP,2,10);var plant=action(FieldworkAction.Operation.PLANT,-1,11);
        assertEquals(1,FieldworkCargo.pending(List.of(cut,plant),List.of()).get("minecraft:oak_sapling#0"));
    }
    @Test void initialSeedIsNotCountedAsProductionOrAReplenishmentProof() {
        var initialUse=action(FieldworkAction.Operation.PLANT,-1,10);
        assertTrue(FieldworkCargo.pending(List.of(initialUse),List.of()).isEmpty());
        var actualDrop=action(FieldworkAction.Operation.CHOP,1,11);
        assertEquals(1,FieldworkCargo.pending(List.of(initialUse,actualDrop),List.of()).get("minecraft:oak_sapling#0"));
    }
    @Test void oldSchemaKeepsOriginalPlantReceiptAndRejectsDuplicateEffect() {
        var data=new FieldworkData();var cut=action(FieldworkAction.Operation.CHOP,2,10);var plant=action(FieldworkAction.Operation.PLANT,-1,11);
        data.record(cut);data.record(plant);data.record(plant);
        var saved=data.save(new CompoundTag(),null);var loaded=FieldworkData.load(saved,null);
        assertEquals(2,loaded.receiptsFor(body).size());assertEquals(data.pendingCargo(body),loaded.pendingCargo(body));
        assertEquals(1,loaded.pendingCargo(body).get("minecraft:oak_sapling#0"));
    }
}
