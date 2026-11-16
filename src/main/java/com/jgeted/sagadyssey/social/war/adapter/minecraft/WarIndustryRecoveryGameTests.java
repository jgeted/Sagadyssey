package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.GsonBuilder;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.war.api.WarMapView;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.nio.file.Files;
import java.util.*;

/** Original farmer and blacksmith obligations across real military labor; no alternative civil executor. */
@GameTestHolder("sagadyssey_war_industry") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarIndustryRecoveryGameTests {
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.industry.test");}
    private static boolean smith(){return System.getProperty("sagadyssey.war.industry.phase","farmer").equals("smith");}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent event){
        if(!enabled()||!(event.getServer() instanceof GameTestServer))return;var l=event.getServer().overworld();l.setDayTime(1000);
        l.getGameRules().set(GameRules.ADVANCE_TIME,false,event.getServer());l.getGameRules().set(GameRules.SPAWN_MOBS,false,event.getServer());l.getGameRules().set(GameRules.RANDOM_TICK_SPEED,0,event.getServer());
        try{l.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_industry:empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));}catch(Exception e){throw new IllegalStateException(e);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_industry",smith()?"smith":"farmer","sagadyssey_war_industry:empty",8000,0,true,WarIndustryRecoveryGameTests::cycle)):List.of();}
    private static void cycle(GameTestHelper h){
        var l=h.getLevel();var s=l.getServer();var origin=h.absolutePos(BlockPos.ZERO).offset(2048,-3,0);
        for(int x=(origin.getX()>>4)-1;x<=(origin.getX()>>4)+1;x++)for(int z=(origin.getZ()>>4)-1;z<=(origin.getZ()>>4)+1;z++)l.setChunkForced(x,z,true);
        for(int x=0;x<14;x++)for(int z=0;z<12;z++){l.setBlockAndUpdate(origin.offset(x,0,z),Blocks.STONE.defaultBlockState());for(int y=1;y<7;y++)l.setBlockAndUpdate(origin.offset(x,y,z),Blocks.AIR.defaultBlockState());}
        var target=origin.offset(5,1,5);var store=origin.offset(8,1,5);l.setBlockAndUpdate(store,Blocks.BARREL.defaultBlockState());var barrel=(Container)l.getBlockEntity(store);
        var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);n.teleportTo(origin.getX()+2.5,origin.getY()+1,origin.getZ()+5.5);n.setOnGround(true);n.setPersistenceRequired();
        var profession=smith()?NpcProfession.BLACKSMITH:NpcProfession.FARMER;n.setProfession(profession);n.setFarmMode(FarmMode.CROP);
        var p=WarMusterGameTests.battlePlayer(h,"IndustryLord",new BlockPos(2,1,8));p.setPos(n.getX(),n.getY(),n.getZ()+3);var actor=CharacterRegistry.currentId(p);var life=NpcLifeData.get(s);life.onRecruited(n,actor,l.getGameTime());
        n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);
        n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(smith()?Items.IRON_PICKAXE:Items.IRON_HOE));n.getPersonalInventory().setItem(0,new ItemStack(Items.IRON_SWORD));n.getPersonalInventory().setItem(1,new ItemStack(Items.BREAD,16));p.getInventory().setItem(3,new ItemStack(Items.EMERALD,16));
        life.setHunger(n.getCharacterId(),300,l.getGameTime());life.updateFatigue(n.getCharacterId(),0);life.setShift(n.getCharacterId(),NpcWorkShift.DAY,l.getGameTime());
        h.assertTrue(life.assignPrivateStorage(s,n.getCharacterId(),l.dimension().identifier().toString(),store,l.getGameTime()),"original private storage assignment");
        h.assertTrue(life.assignEmployerWorkZone(n,actor,target,target),"original work zone");NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);n.setCommand(NpcCommand.WORK);
        UUID container=ProductionContainerIds.resolve(s,l.dimension().identifier().toString(),store);var person=SubjectRef.character(n.getCharacterId());
        if(smith()){
            l.setBlockAndUpdate(target,Blocks.ANVIL.defaultBlockState());barrel.setItem(0,new ItemStack(Items.IRON_INGOT,6));barrel.setItem(1,new ItemStack(Items.COAL,2));RightsStateData.get(s).ensureContext(container,"fixture:original_forge",person,person,AccessPolicy.CONTRACT);
        }else{
            l.setBlockAndUpdate(target.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));l.setBlockAndUpdate(target,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));n.getPersonalInventory().setItem(2,new ItemStack(Items.WHEAT_SEEDS,16));
        }
        WarMusterGameTests.command(p,"saga war muster create IndustryArmy");
        var product=smith()?Items.IRON_PICKAXE:Items.WHEAT;var runtime=WarMusterRuntime.existing(s).orElseThrow();var army=WarMusterEntry.armyId(actor,"IndustryArmy");var unit=WarMusterEntry.unitId(army,"Workers");UUID[] contract={null};ProductionOrder[] orders=new ProductionOrder[2];
        h.onEachTick(()->{if(contract[0]!=null&&runtime.book().offer(contract[0]).filter(o->o.stage()==WarMusterEntry.Stage.PREPARING).isPresent())p.setPos(n.getX(),n.getY(),n.getZ()+3);});
        var sequence=h.startSequence().thenWaitUntil(()->h.assertTrue(runtime.book().owns(actor,army),"army durable"))
          .thenExecute(()->WarMusterGameTests.command(p,"saga war muster unit IndustryArmy Workers sword"))
          .thenWaitUntil(()->h.assertTrue(runtime.book().unit(unit).filter(u->u.recorded()&&runtime.book().durable(u)).isPresent(),"unit durable"));
        if(smith())sequence.thenExecute(()->{orders[0]=order(n,container,target,store,600);ProductionOrderData.get(s).put(orders[0]);})
          .thenWaitUntil(()->h.assertTrue(ProductionLaborData.get(s).worked(orders[0].id())>=10&&ProductionLaborData.get(s).worked(orders[0].id())<500,"original forge already working before military offer"));
        else sequence.thenWaitUntil(()->h.assertTrue(count(barrel,product)==1&&l.getBlockState(target).is(Blocks.WHEAT)&&l.getBlockState(target).getValue(CropBlock.AGE)==0,"original harvest, replant and private delivery before military service"));
        sequence.thenExecute(()->contract[0]=WarMusterGameTests.battleOffer(h,p,n,"IndustryArmy","Workers"));
        if(smith())sequence.thenWaitUntil(()->h.assertTrue(runtime.book().offer(contract[0]).filter(o->o.stage()==WarMusterEntry.Stage.PREPARING&&runtime.book().durable(o)).isPresent(),"accepted military decision waits for original forge obligation"))
          .thenIdle(40).thenExecute(()->{
            h.assertTrue(!WorkCommitmentRuntime.blocksCivil(n)&&ProductionOrderData.get(s).byId(orders[0].id()).orElseThrow().status()!=ProductionOrderStatus.COMPLETED,"no military claim or forced forge cancellation");
            h.assertTrue(ProductionLaborData.get(s).worked(orders[0].id())>10,"original forge continues during handoff wait");
          });
        sequence.thenWaitUntil(()->h.assertTrue(runtime.book().offer(contract[0]).filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE&&runtime.book().durable(o)).isPresent()&&count(barrel,product)==1,"military starts only after original responsibility settles: "+runtime.status(actor)))
          .thenExecute(()->{
            var at=origin.offset(4,1,7);var map=MapItem.create(l,at.getX(),at.getZ(),(byte)0,true,true);p.setItemSlot(EquipmentSlot.MAINHAND,map);Arrays.fill(MapItem.getSavedData(map,l).colors,(byte)40);var maps=WarMapRuntime.get(s);var v=maps.begin(p).view().orElseThrow();
            var preview=maps.act(p,new Request(v.session(),v.revision(),Action.PREVIEW,0,unit,WarMapView.Mode.HOLD,(int)Math.round(v.sheet().pixelX(at.getX())),(int)Math.round(v.sheet().pixelZ(at.getZ())),new UUID(0,0))).view().orElseThrow();h.assertTrue(preview.preview().isPresent(),"original map preview");
            var receipt=maps.act(p,new Request(preview.session(),preview.revision(),Action.ISSUE,0,unit,WarMapView.Mode.HOLD,0,0,preview.preview().orElseThrow().ticket())).view().orElseThrow().receipt().orElseThrow();h.assertTrue(receipt.outcome()==WarMapView.Outcome.ACCEPTED,"original map confirmation");
          }).thenWaitUntil(()->h.assertTrue(n.getMainHandItem().is(Items.IRON_SWORD),"actual military arming retains original tools"))
          .thenExecute(()->{
            if(smith()){orders[1]=order(n,container,target,store,80);ProductionOrderData.get(s).put(orders[1]);}
            else l.setBlockAndUpdate(target,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7)); // second mature crop is an explicit scene condition
          }).thenIdle(240).thenExecute(()->{
            h.assertTrue(WorkCommitmentRuntime.blocksCivil(n)&&count(barrel,product)==1,"no parallel civilian production while serving");
            if(smith())h.assertTrue(ProductionLaborData.get(s).worked(orders[1].id())==0&&count(barrel,Items.IRON_INGOT)==3&&count(barrel,Items.COAL)==1,"second authorized forge order does not reserve/consume or gain labor during military service");
            else h.assertTrue(l.getBlockState(target).getValue(CropBlock.AGE)==7,"actual crop remains unharvested during service");
            WarMusterGameTests.command(p,"saga war muster retire "+contract[0]);
          }).thenWaitUntil(()->h.assertTrue(runtime.book().offer(contract[0]).filter(o->o.stage()==WarMusterEntry.Stage.ENDED&&runtime.book().durable(o)).isPresent()&&!WorkCommitmentRuntime.blocksCivil(n),"original shared labor released"))
          .thenWaitUntil(()->{
            h.assertTrue(count(barrel,product)==2,"same original industry actually produces and delivers again after retirement; "+n.getWorkActivity()+" / "+n.getWorkActivityDetail());
            if(smith())h.assertTrue(ProductionOrderData.get(s).byId(orders[1].id()).orElseThrow().status()==ProductionOrderStatus.COMPLETED&&ProductionLaborData.get(s).worked(orders[0].id())==600&&ProductionLaborData.get(s).worked(orders[1].id())==80&&count(barrel,Items.IRON_INGOT)==0&&count(barrel,Items.COAL)==0,"original forge inputs/labor produce exactly two outputs");
            else h.assertTrue(FieldworkData.get(s).receiptsFor(n.getUUID()).stream().filter(r->r.action().operation()==FieldworkAction.Operation.HARVEST).count()==2&&FieldworkData.get(s).receiptsFor(n.getUUID()).stream().filter(r->r.action().operation()==FieldworkAction.Operation.PLANT).count()==2&&l.getBlockState(target).getValue(CropBlock.AGE)==0,"two actual harvests and two actual replant/seed consumption receipts");
            var provisions=WarServerEvents.existing(s).orElseThrow().nativeMobilization().orElseThrow().provisions();var c=provisions.find(contract[0]).orElseThrow();h.assertTrue(c.pending().isEmpty()&&provisions.durable(contract[0])&&c.marks().containsKey(ServiceProvision.Step.CLOSE_PAYROLL)&&c.arrears(l.getGameTime()).signum()==0,"funded closing payroll acknowledged");
          }).thenExecute(()->{
            h.assertTrue(n.getProfession()==profession&&n.getCommand()==NpcCommand.WORK&&NpcBrainRuntime.mode(n)==NpcBehaviorMode.PLANNED&&target.equals(n.getWorkZoneMin())&&store.equals(life.record(n.getCharacterId()).privateStoragePosition()),"original civilian context retained");
            h.assertTrue(p.getInventory().countItem(Items.EMERALD)+n.getPersonalInventory().count(i->i.is(Items.EMERALD))==16,"actual funds conserved");
            var proof=new LinkedHashMap<String,Object>();proof.put("process",ProcessHandle.current().pid());proof.put("profession",profession);proof.put("body",n.getUUID());proof.put("person",n.getCharacterId());proof.put("contract",contract[0]);proof.put("products",count(barrel,product));proof.put("product",product.toString());proof.put("originalPrivateStorage",store.toShortString());proof.put("originalWorkZone",target.toShortString());proof.put("originalProductionResumed",true);proof.put("actualBusyForgeHandoff",smith());proof.put("forgeOrders",smith()?List.of(orders[0].id(),orders[1].id()):List.of());proof.put("actualForgeLabor",smith()?List.of(ProductionLaborData.get(s).worked(orders[0].id()),ProductionLaborData.get(s).worked(orders[1].id())):List.of());proof.put("backgroundOnly",true);
            try{Files.writeString(s.getWorldPath(LevelResource.ROOT).resolve("war-industry-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(proof));}catch(Exception e){throw new IllegalStateException(e);}
          }).thenSucceed();
    }
    private static ProductionOrder order(NpcBase n,UUID container,BlockPos target,BlockPos store,int labor){var owner=SubjectRef.character(n.getCharacterId());String d=n.level().dimension().identifier().toString();return ProductionOrder.open(UUID.randomUUID(),owner,owner,"sagadyssey:test_iron_pickaxe",List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)),3),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.COAL)),1)),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE)),1),labor,UUID.randomUUID(),d,target,container,d,store,n.level().getGameTime(),"original civil forge obligation across military service");}
    private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
}
