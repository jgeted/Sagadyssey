package com.jgeted.sagadyssey.npc.brain.command;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NpcCombatClientFixtureDataTest {
    private CompoundTag ready() {
        var row=new CompoundTag();
        row.putString("Dimension","minecraft:overworld");
        row.putString("State","READY");
        row.putLong("Anchor",0);row.putLong("Created",100);
        row.putLong("Box",1);row.putLong("Destination",2);
        row.putBoolean("Interrupted",true);
        for(var key:List.of("Smith","Doctor","Patient","Guard","Carrier","Order","Medical","Need","Withdrawal"))row.putUUID(key,UUID.randomUUID());
        return row;
    }
    @Test void originalObjectsAndCompletedInterruptionSurviveReload() {
        var data=new NpcCombatClientFixture.Data();var person=UUID.randomUUID();var row=ready();data.put(person,row);
        var restored=NpcCombatClientFixture.Data.load(data.save(new CompoundTag(),null),null).find(person);
        for(var key:List.of("Smith","Doctor","Patient","Guard","Carrier","Order","Medical","Need","Withdrawal"))assertEquals(row.getUUID(key),restored.getUUID(key));
        assertTrue(restored.getBoolean("Interrupted"));
    }
    @Test void missingRecoveryMarkerCannotSilentlyRepeatWithdrawal() {
        var data=new NpcCombatClientFixture.Data();data.put(UUID.randomUUID(),ready());var saved=data.save(new CompoundTag(),null);
        saved.getList("Fixtures",Tag.TAG_COMPOUND).getCompound(0).remove("Interrupted");
        assertThrows(IllegalArgumentException.class,()->NpcCombatClientFixture.Data.load(saved,null));
    }
    @Test void uncertainIntentIsRetainedInsteadOfRecreatedAsReady() {
        var data=new NpcCombatClientFixture.Data();var person=UUID.randomUUID();var row=ready();row.putString("State","INTENT");row.remove("Smith");data.put(person,row);
        assertEquals("INTENT",NpcCombatClientFixture.Data.load(data.save(new CompoundTag(),null),null).find(person).getString("State"));
    }
    @Test void callerCannotMutateSavedFixtureIdentity() {
        var data=new NpcCombatClientFixture.Data();var person=UUID.randomUUID();var row=ready();data.put(person,row);var id=row.getUUID("Order");row.remove("Order");data.find(person).remove("Order");assertEquals(id,data.find(person).getUUID("Order"));
    }
}
