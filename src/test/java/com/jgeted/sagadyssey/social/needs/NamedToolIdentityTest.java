package com.jgeted.sagadyssey.social.needs;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class NamedToolIdentityTest {
 private ProductionMaterial m(String item,int n){return new ProductionMaterial(new ItemFingerprint("minecraft:"+item,0),n);}
 private boolean valid(String item,List<ProductionMaterial> inputs,int components){
  var owner=SubjectRef.character(UUID.randomUUID());var target=new FieldworkStorage("minecraft:overworld",BlockPos.ZERO,UUID.randomUUID(),owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"named-tools");
  var r=NeedRequest.open(new NeedRequest.Key(owner.id(),"CHARACTER:workshop:tool",new ItemFingerprint("minecraft:"+item,components),target.containerId(),0,10000),1,32,1);var scope=new SupplyFlowData.Demand(owner,target.containerId());
  var o=ProductionOrder.open(SupplyFlowService.unitOrderId(r.id(),0),owner,owner,"minecraft:"+item,inputs,m(item,1),80,SupplyFlowService.unitBatchId(r.id(),0),target.dimension(),BlockPos.ZERO,target.containerId(),target.dimension(),target.position(),1,"named exact tool");
  return SupplyFlowService.unitProductionIntent(r,scope,target,o,0);
 }
 @Test void exactAxeInputs(){assertTrue(valid("iron_axe",List.of(m("iron_ingot",3),m("stick",2)),0));assertFalse(valid("iron_axe",List.of(m("iron_ingot",2),m("stick",2)),0));}
 @Test void exactHoeInputs(){assertTrue(valid("iron_hoe",List.of(m("iron_ingot",2),m("stick",2)),0));assertFalse(valid("iron_hoe",List.of(m("iron_ingot",3),m("stick",2)),0));}
 @Test void exactShearsWithoutInventedFuel(){assertTrue(valid("shears",List.of(m("iron_ingot",2)),0));assertFalse(valid("shears",List.of(m("iron_ingot",2),m("coal",1)),0));}
 @Test void ArbitraryToolsAndComponentsRemainClosed(){assertFalse(valid("diamond_axe",List.of(m("diamond",3),m("stick",2)),0));assertFalse(valid("iron_axe",List.of(m("iron_ingot",3),m("stick",2)),12));}
}