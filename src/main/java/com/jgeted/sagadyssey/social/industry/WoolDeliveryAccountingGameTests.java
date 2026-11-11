package com.jgeted.sagadyssey.social.industry;

import net.minecraft.world.entity.animal.sheep.Sheep;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;
import static com.jgeted.sagadyssey.social.industry.IndustryCompletionGameTests.*;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.settlement.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;
import net.neoforged.neoforge.gametest.*;


/** New observation contract: preserve every need receipt; count physical deliveries to the real shared workshop. */
@GameTestHolder("sagadyssey_industries_completion") @PrefixGameTestTemplate(false)
public final class WoolDeliveryAccountingGameTests {
 @GameTestGenerator public static Collection<TestFunction> tests(){
  return Boolean.getBoolean("sagadyssey.industry.native")&&System.getProperty("sagadyssey.completion.phase","").equals("wool-accounted-resume")
   ?List.of(new TestFunction("wool_accounting","wool.accounted_original_two_beds","sagadyssey_industries_completion:empty",48000,0,true,WoolDeliveryAccountingGameTests::resume)):List.of();
 }
 private static int confirmedWool(net.minecraft.server.MinecraftServer server,UUID source,UUID target,UUID owner,UUID onlyNeed,long through){
  var flows=SupplyFlowData.get(server);var ledger=TransferLedgerData.get(server);var seen=new HashSet<UUID>();int total=0;
  for(var audit:ledger.audits()){
   if(!seen.add(audit.transferId())||!audit.lawful()||audit.completedAt()>through)continue;
   var receipt=ledger.receipt(audit.transferId());var identity=ledger.requestIdentity(audit.transferId());if(receipt==null||!receipt.successful()||!receipt.lawful()||identity==null)continue;
   var route=flows.routes().stream().filter(r->r.token().equals(audit.authority())&&r.stage()==SupplyFlowData.Stage.DONE&&r.pending()==null&&r.source().equals(source)&&r.destination().equals(target)&&r.principal().equals(SubjectRef.character(owner))&&(onlyNeed==null||r.need().equals(onlyNeed))).findFirst().orElse(null);if(route==null)continue;
   var legs=MinecraftApi.listOfType(identity, "Legs",Tag.TAG_COMPOUND);if(legs.size()!=1)continue;var leg=legs.getCompoundOrEmpty(0);
   if(!leg.read("Source", UUIDUtil.CODEC).isPresent()||!leg.read("Source", UUIDUtil.CODEC).orElseThrow().equals(ContainerIds.npcCargo(route.carrierCharacter()))||!leg.read("Destination", UUIDUtil.CODEC).isPresent()||!leg.read("Destination", UUIDUtil.CODEC).orElseThrow().equals(target)||!ItemFingerprint.load(leg.getCompoundOrEmpty("Item")).equals(fp("white_wool"))||leg.getIntOr("Quantity", 0)!=route.quantity())continue;
   total+=route.quantity();
  }return total;
 }
 private static void resume(GameTestHelper h){
  if(prior==null)throw new IllegalStateException("same complete wool world required");var l=h.getLevel();var s=l.getServer();h.onEachTick(()->{if(l.getGameTime()-prior.getLongOr("Start", 0L)>48000)h.fail("original48000 total tick bound reached");});sprint(h,48500);
  h.succeedWhen(()->{
   var owner=original(h,"Owner");original(h,"Courier");original(h,"Carpenter");var smith=original(h,"Smith");h.assertTrue(l.getEntity(prior.read("Sheep", UUIDUtil.CODEC).orElseThrow()) instanceof Sheep,"same original sheep loaded");h.assertTrue(l.getGameTime()-prior.getLongOr("Start", 0L)<=48000,"original total wool tool/new-need bound");
   var data=EntityResourceActionData.existing(s);h.assertTrue(data.actions().stream().noneMatch(a->a.getStringOr("State", "").equals("REVIEW")),"no uncertain native result");
   h.assertTrue(data.sources().stream().filter(x->x.read("Target", UUIDUtil.CODEC).orElseThrow().equals(prior.read("Sheep", UUIDUtil.CODEC).orElseThrow())).map(x->x.read("Need", UUIDUtil.CODEC).orElseThrow()).distinct().count()>=2,"same owned sheep formally bound to a NEW real policy need");
   var originalNeed=NeedRequestData.get(s).request(prior.read("Need", UUIDUtil.CODEC).orElseThrow());
   h.assertTrue(originalNeed.originalRequested()==3&&originalNeed.key().item().equals(fp("white_wool")),"original demand identity and literal delivered counter retained");
   var originalStock=FieldworkStorage.load(prior.getCompoundOrEmpty("Stock"));var originalOutput=FieldworkStorage.load(prior.getCompoundOrEmpty("Output"));
   var firstOrder=ProductionOrderData.get(s).byId(prior.read("BedOne", UUIDUtil.CODEC).orElseThrow()).orElseThrow();var firstBatch=ProductionBatchData.get(s).batch(firstOrder.batchId());h.assertTrue(firstBatch!=null&&firstBatch.status()==ProductionBatchStatus.COMPLETED,"actual first material consumption batch exists");
   int firstNeedReceipts=confirmedWool(s,originalOutput.containerId(),originalStock.containerId(),owner.getCharacterId(),prior.read("Need", UUIDUtil.CODEC).orElseThrow(),Long.MAX_VALUE);
   int beforeFirstBed=confirmedWool(s,originalOutput.containerId(),originalStock.containerId(),owner.getCharacterId(),null,firstBatch.startedAt());
   h.assertTrue(originalNeed.delivered()==firstNeedReceipts,"original need never credited for another need's real delivery");
   h.assertTrue(beforeFirstBed>=3,"three real authorized wool arrived before first bed work, including legitimate competing obligations");
   h.assertTrue(originalNeed.delivered()==3||originalNeed.status()==NeedRequestStatus.CANCELLED&&originalNeed.reserved()==0&&originalNeed.inTransit()==0,"only legitimately ended uncommitted buffer may differ from literal3-delivery assertion");

   for(String key:List.of("BedOne","BedTwo"))h.assertTrue(ProductionOrderData.get(s).byId(prior.read(key, UUIDUtil.CODEC).orElseThrow()).orElseThrow().status()==ProductionOrderStatus.COMPLETED,"two real beds consumed six actual wool and planks");
   var forged=ProductionOrderData.get(s).ordersForWorker(smith.getCharacterId()).stream().filter(o->o.recipeId().equals("minecraft:shears")&&o.status()==ProductionOrderStatus.COMPLETED).toList();h.assertTrue(forged.size()==1,"one replacement shear really forged from finite two iron");h.assertTrue(owner.getMainHandItem().is(Items.SHEARS)&&owner.getMainHandItem().getDamageValue()>0,"original owner legitimately acquired and used replacement shears");
   var target=FieldworkStorage.load(prior.getCompoundOrEmpty("Stock"));var output=FieldworkStorage.load(prior.getCompoundOrEmpty("Output"));h.assertTrue(count(box(h,target),Items.WHITE_BED)==2&&count(box(h,target),Items.OAK_PLANKS)==0&&count(box(h,output),Items.IRON_INGOT)==0,"real beds and real replacement inputs");
   int produced=data.actions().stream().flatMap(a->MinecraftApi.listOfType(a, "Drops",Tag.TAG_COMPOUND).stream()).map(x->(CompoundTag)x).filter(x->x.getCompoundOrEmpty("Item").getStringOr("Item", "").equals("minecraft:white_wool")).mapToInt(x->x.getIntOr("Quantity", 0)).sum();int physical=count(box(h,target),Items.WHITE_WOOL)+count(box(h,output),Items.WHITE_WOOL);
   for(String key:List.of("Owner","Courier","Carpenter","Smith")){var n=original(h,key);physical+=n.getPersonalInventory().count(i->i.is(Items.WHITE_WOOL))+count(n.getTransportInventory(),Items.WHITE_WOOL);}h.assertTrue(produced==physical+6,"native wool equals locations plus six real bed inputs");
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("WOOL_ACCOUNTING originalNeedDelivered={} originalNeedStatus={} confirmedBeforeFirstBed={} cancelledNative={}",originalNeed.delivered(),originalNeed.status(),beforeFirstBed,data.actions().stream().filter(x->x.getStringOr("State", "").equals("CANCELLED")).count());
   var checkpoint=prior.copy();checkpoint.putInt("OriginalNeedLiteralDelivered",originalNeed.delivered());checkpoint.putInt("ConfirmedBeforeFirstBed",beforeFirstBed);save(h,checkpoint);
   com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("ACCOUNTED_WOOL_ACCEPT tick={} person={} sheep={} toolForge={} producedWool={} consumedWool=6 physicalWool={} beds=2 newNeed=true",l.getGameTime(),owner.getCharacterId(),prior.read("Sheep", UUIDUtil.CODEC).orElseThrow(),forged.getFirst().id(),produced,physical);
  });
 }
}
