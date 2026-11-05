package com.jgeted.sagadyssey.core.structure;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
public final class NaturalVillageShortageGameTests {
 static Collection<TestFunction> tests(){var tests=new ArrayList<TestFunction>();int index=0;for(String failure:List.of("unripe","exhausted","denied","blocked","full","missing_carrier","control")){
  final int offset=256*index++;tests.add(new TestFunction("r1_shortage",failure,"sagadyssey_society_r1:empty",8000,0,true,h->run(h,failure,offset)));}return tests;}
 private static int food(Container c){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(Items.CARROT))n+=c.getItem(i).getCount();return n;}
 private static void run(GameTestHelper h,String failure,int offset){var b=NaturalVillageR1GameTests.fixture(h,offset);var l=h.getLevel();
  // A frozen zero-growth transport fixture isolates one obstruction; the normal-growth acceptance is separate.
  l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,l.getServer());var restored=new boolean[]{false};var cage=new ArrayList<BlockPos>();
  h.runAfterDelay(240,()->{var state=NaturalVillageLifeData.get(l.getServer()).village(b.buildingKey());h.assertTrue(state.getBoolean("Ready"),"initialized natural household");
   var farmer=NaturalVillageR1GameTests.actor(l,b,0);var worker=NaturalVillageR1GameTests.actor(l,b,2);var courier=NaturalVillageR1GameTests.actor(l,b,1);
   if(failure.equals("unripe"))return;
   for(int i=0;i<3;i++)NpcLifeData.get(l.getServer()).setHunger(NaturalVillageR1GameTests.actor(l,b,i).getCharacterId(),900,l.getGameTime());
   if(failure.equals("exhausted")){farmer.getPersonalInventory().clearContent();for(var p:NaturalVillageLayout.crops())l.setBlockAndUpdate(NaturalVillageLayout.at(b,p),Blocks.AIR.defaultBlockState());return;}
   farmer.setNoAi(true);worker.setNoAi(true);var raw=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.RAW));
   for(int i=0;i<farmer.getPersonalInventory().getContainerSize();i++)if(farmer.getPersonalInventory().getItem(i).is(Items.CARROT)){raw.setItem(0,farmer.getPersonalInventory().removeItemNoUpdate(i));}
   if(failure.equals("denied")){var store=ContainerIds.block("private_storage",b.dimension(),NaturalVillageLayout.at(b,NaturalVillageLayout.RAW));RightsStateData.get(l.getServer()).revoke(ContainerIds.named("natural-source:"+NaturalVillageLifeData.home(b)+":"+store));}
   if(failure.equals("blocked")){var c=courier.blockPosition();for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=0;y<=3;y++)if(Math.abs(x)==2||Math.abs(z)==2||y==3){var p=c.offset(x,y,z);if(l.getBlockState(p).isAir()){cage.add(p);l.setBlockAndUpdate(p,Blocks.BEDROCK.defaultBlockState());}}}
   if(failure.equals("full")){var pantry=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY));for(int i=0;i<pantry.getContainerSize();i++)pantry.setItem(i,new ItemStack(Items.COBBLESTONE,64));}
   if(failure.equals("missing_carrier"))courier.discard();
  });
  h.runAfterDelay(1800,()->{var farmer=NaturalVillageR1GameTests.actor(l,b,0);var pantry=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY));
   var courier=NaturalVillageR1GameTests.actor(l,b,1);
   if(failure.equals("unripe")){h.assertTrue(FieldworkData.get(l.getServer()).receiptsFor(farmer.getUUID()).stream().noneMatch(r->r.action().operation()==FieldworkAction.Operation.HARVEST),"unripe crop never creates harvest");h.succeed();return;}
   if(failure.equals("control")){restored[0]=true;return;}
   if(failure.equals("exhausted")){h.assertTrue(food(farmer.getPersonalInventory())==0&&food(pantry)==0&&NpcLifeData.get(l.getServer()).needs(farmer.getCharacterId()).hunger()>900,"exhaustion leaves real shortage and growing hunger");h.succeed();return;}
   h.assertTrue(food(pantry)==0,"obstruction never declares fictitious delivery");
   if(courier!=null)h.assertTrue(NpcLifeData.get(l.getServer()).needs(courier.getCharacterId()).hunger()>900,"lack of supply has real hunger consequence");
   if(failure.equals("missing_carrier")){h.assertTrue(NaturalVillageR1GameTests.actor(l,b,1)==null,"missing original carrier is not respawned");h.succeed();return;}
   if(failure.equals("denied")){var store=ContainerIds.block("private_storage",b.dimension(),NaturalVillageLayout.at(b,NaturalVillageLayout.RAW));RightsStateData.get(l.getServer()).grant(new RightGrant(ContainerIds.named("natural-source:"+NaturalVillageLifeData.home(b)+":"+store),store,SubjectRef.household(NaturalVillageLifeData.home(b)),Set.of(AssetRight.TRANSFER),"test restore original source grant",l.getGameTime(),null,true));}
   if(failure.equals("blocked"))for(var p:cage)l.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());
   if(failure.equals("full")){var escrow=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC));for(int i=0;i<pantry.getContainerSize();i++)escrow.setItem(i,pantry.removeItemNoUpdate(i));}
   restored[0]=true;
   Sagadyssey.LOGGER.info("R1_SHORTAGE_CONFIRMED case={} routes={}",failure,SupplyFlowData.get(l.getServer()).routes().stream().filter(r->r.principal().equals(SubjectRef.household(NaturalVillageLifeData.home(b)))).map(r->r.stage()+":"+r.detail()).toList());
  });
  h.succeedWhen(()->{h.assertTrue(restored[0],"wait for obstruction observation");var courier=NaturalVillageR1GameTests.actor(l,b,1);h.assertTrue(courier!=null&&NpcLifeData.get(l.getServer()).needs(courier.getCharacterId()).lastAteAt()>240,"actual delivery feeds after restored condition");Sagadyssey.LOGGER.info("R1_SHORTAGE_RECOVERY case={}",failure);});
 }
}
