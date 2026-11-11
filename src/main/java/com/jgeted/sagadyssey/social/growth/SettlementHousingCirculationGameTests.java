package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** First real construction/arrival, then bounded circulation. No prebuilt homes or moved bodies. */
@GameTestHolder("sagadyssey_development") @PrefixGameTestTemplate(false)
public final class SettlementHousingCirculationGameTests {
 @GameTestGenerator public static Collection<TestFunction> tests(){
  String p=System.getProperty("sagadyssey.development.phase","");
  if(!Set.of("counter-circulation-v8","counter-circulation-v7").contains(p))return List.of();
  return List.of(new TestFunction("development",p,"sagadyssey_development:empty",120000,0,true,h->run(h,p.endsWith("v8"))));
 }
 static void run(GameTestHelper h,boolean wide){
  var l=h.getLevel();var s=l.getServer();
  var target=NaturalVillageR1GameTests.fixture(h,0,wide?SettlementDevelopmentLayout.TEMPLATE:SettlementDevelopmentLayout.LEGACY_TEMPLATE);
  var a=NaturalVillageR1GameTests.fixture(h,256,NaturalVillageLayout.FARM_V3);var b=NaturalVillageR1GameTests.fixture(h,512,NaturalVillageLayout.FARM_V3);var villages=List.of(target,a,b);
  var pieces=SettlementDevelopmentLayout.house(target,0);
  h.assertTrue(pieces.stream().filter(p->p.material()==Items.COBBLESTONE).count()==(wide?64:55),"exact versioned stone cost");
  h.assertTrue(pieces.size()==(wide?69:60),"one door, three beds, one pantry; no free extra parts");
  long[] start={-1},arrived={-1};var exited=new HashSet<UUID>();
  s.tickRateManager().requestGameToSprint(120000);
  h.onEachTick(()->{
   var d=SettlementDevelopmentData.existing(s);var life=NaturalVillageLifeData.existing(s);long now=l.getGameTime();
   if(d==null||life==null||d.site(target.buildingKey())==null||villages.stream().anyMatch(v->{var t=life.village(v.buildingKey());return t==null||!t.getBooleanOr("Ready", false);}))return;
   if(start[0]<0){SettlementDevelopmentGameTests.observeRoute(l,villages);SettlementDevelopmentGameTests.configureInitial(l,target,a,b);start[0]=now;Sagadyssey.LOGGER.info("HOUSING_CIRCULATION_INITIAL template={} people=9 targetPantry=64 sources=64each publicStone=165 sourceBodiesNotMoved=true width={} stonePerHouse={}",target.templateId(),SettlementDevelopmentLayout.width(target),SettlementDevelopmentLayout.stoneCost(target));return;}
   var projects=MinecraftApi.listOfType(d.site(target.buildingKey()), "Projects",Tag.TAG_COMPOUND);if(projects.isEmpty())return;var p=projects.getCompoundOrEmpty(0);
   h.assertTrue((p.contains("Built")?p.getLongOr("Built", 0L):now)-p.getLongOr("Created", 0L)<=48000,"original construction deadline remains fixed");
   var app=d.application(p.read("Household", UUIDUtil.CODEC).orElseThrow());if(app.contains("Departed"))h.assertTrue((app.contains("Arrived")?app.getLongOr("Arrived", 0L):now)-app.getLongOr("Departed", 0L)<=36000,"normal walking arrival deadline remains fixed");
   if(!app.contains("Arrived"))return;if(arrived[0]<0)arrived[0]=app.getLongOr("Arrived", 0L);
   h.assertTrue(now-arrived[0]<=24000,"circulation and real food work must resume within one normal day, no timeout extension");
   var home=HouseholdData.get(s).byId(p.read("Household", UUIDUtil.CODEC).orElseThrow());
   var base=SettlementDevelopmentLayout.localLot(0);var min=NaturalVillageLayout.at(target,base.offset(0,0,0));var max=NaturalVillageLayout.at(target,base.offset(SettlementDevelopmentLayout.width(target)-1,3,4));
   var box=new AABB(Math.min(min.getX(),max.getX()),min.getY(),Math.min(min.getZ(),max.getZ()),Math.max(min.getX(),max.getX())+1,max.getY()+1,Math.max(min.getZ(),max.getZ())+1);
   var registry=com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(s);
   boolean ate=true;for(var m:home.members()){
    var ref=registry.bodyOf(m.characterId());h.assertTrue(ref!=null&&l.getEntity(ref.entityId()) instanceof NpcBase,"original body must remain present");var n=(NpcBase)l.getEntity(ref.entityId());
    if(!n.getBoundingBox().intersects(box))exited.add(n.getCharacterId());var needs=NpcLifeData.get(s).needs(m.characterId());ate&=needs!=null&&needs.lastAteAt()>arrived[0];
   }
   if(now%2000==0)Sagadyssey.LOGGER.info("HOUSING_CIRCULATION_PROGRESS width={} elapsed={} afterArrival={} originalResidentsExited={} foodCycles={} allAte={}",SettlementDevelopmentLayout.width(target),now-start[0],now-arrived[0],exited.size(),p.getIntOr("CompletedFoodCycles", 0),ate);
   if(exited.size()==3&&p.getIntOr("CompletedFoodCycles", 0)>=1&&ate){
    var proof=new CompoundTag();proof.putLong("Started",start[0]);proof.putLong("Arrived",arrived[0]);proof.putLong("At",now);proof.putString("Template",target.templateId());proof.putInt("Width",SettlementDevelopmentLayout.width(target));proof.putInt("ExitedOriginalResidents",exited.size());proof.put("Development",d.save(new CompoundTag(),s.registryAccess()));proof.put("Fieldwork",FieldworkData.get(s).save(new CompoundTag(),s.registryAccess()));proof.put("Life",NpcLifeData.get(s).save(new CompoundTag(),s.registryAccess()));
    try{NbtIo.writeCompressed(proof,s.getWorldPath(LevelResource.ROOT).resolve("housing-circulation-proof.nbt"));}catch(Exception x){throw new IllegalStateException(x);}
    Sagadyssey.LOGGER.info("HOUSING_CIRCULATION_ACCEPT width={} firstHouseAndOriginalFamily=true all3WalkedOut=true realFoodPipeline=true all3Ate=true elapsed={} afterArrival={} fullTwoRoundAcceptance=false",SettlementDevelopmentLayout.width(target),now-start[0],now-arrived[0]);h.succeed();
   }
  });
 }
}
