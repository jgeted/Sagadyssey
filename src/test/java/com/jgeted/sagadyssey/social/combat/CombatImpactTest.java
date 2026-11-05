package com.jgeted.sagadyssey.social.combat;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CombatImpactTest {
    private CombatImpact impact(){return new CombatImpact(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,1,3,true,500);}
    @Test void exactDomainContextAndCountsRoundtrip(){var map=new HashMap<UUID,CombatImpact>();var i=impact();CombatImpact.put(map,i);var t=new CompoundTag();t.put("CombatImpacts",CombatImpact.save(map));var reloaded=new HashMap<UUID,CombatImpact>();CombatImpact.load(t,reloaded);assertEquals(map,reloaded);}
    @Test void settlementReplaysWithoutSecondImpact(){var map=new HashMap<UUID,CombatImpact>();var i=impact();assertTrue(CombatImpact.put(map,i));assertFalse(CombatImpact.put(map,i));assertEquals(1,map.size());}
    @Test void cannotInventDifferentCargoLossForSameReceipt(){var map=new HashMap<UUID,CombatImpact>();var i=impact();CombatImpact.put(map,i);assertThrows(IllegalStateException.class,()->CombatImpact.put(map,new CombatImpact(i.encounter(),i.character(),i.household(),i.employer(),i.settlement(),2,1,4,true,500)));}
    @Test void oldDomainDataDoesNotFabricateBattleResults(){var map=new HashMap<UUID,CombatImpact>();CombatImpact.load(new CompoundTag(),map);assertTrue(map.isEmpty());}
    @Test void malformedReceiptRejectedRatherThanSilentlyDropped(){var t=new CompoundTag();var list=new ListTag();var i=impact().save();i.remove("Shots");list.add(i);t.put("CombatImpacts",list);assertThrows(IllegalArgumentException.class,()->CombatImpact.load(t,new HashMap<>()));}
}
