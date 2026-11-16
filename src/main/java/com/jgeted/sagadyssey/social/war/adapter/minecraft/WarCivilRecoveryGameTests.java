package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.api.WarMapView;
import com.jgeted.sagadyssey.social.war.api.WarFieldReport;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import com.jgeted.sagadyssey.social.work.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Real original work -> military claim -> release -> original work, without a graphical client. */
@GameTestHolder("sagadyssey_war_civil") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarCivilRecoveryGameTests {
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.civil.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.civil.phase","planned");}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent event){
        if(!enabled()||!(event.getServer() instanceof GameTestServer))return;
        var l=event.getServer().overworld();l.setDayTime(1000);
        if(phase().equals("resume")){
            var saved=read(event.getServer());
            event.getServer().getWorldData().overworldData().setGameTime(saved.get("stoppedGameTime").getAsLong());
            event.getServer().getWorldData().overworldData().setDayTime(saved.get("stoppedDayTime").getAsLong());
        }
        l.getGameRules().set(GameRules.ADVANCE_TIME,false,event.getServer());
        l.getGameRules().set(GameRules.SPAWN_MOBS,false,event.getServer());
        try{l.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_civil:empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){
        if(!enabled()||!phase().equals("save")||!Files.isRegularFile(path(event.getServer())))return;
        try{
            var saved=read(event.getServer());var level=NbtIo.readCompressed(event.getServer().getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.create(16L*1024*1024)).getCompoundOrEmpty("Data");
            saved.addProperty("stoppedGameTime",level.getLongOr("Time",0));saved.addProperty("stoppedDayTime",level.getLongOr("DayTime",0));write(event.getServer(),saved);
        }catch(Exception e){throw new IllegalStateException("stopped civil recovery clock",e);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_civil",phase(),"sagadyssey_war_civil:empty",5000,0,true,phase().equals("resume")?WarCivilRecoveryGameTests::resume:WarCivilRecoveryGameTests::cycle)):List.of();}
    private static void cycle(GameTestHelper h){
        var l=h.getLevel();var s=l.getServer();var origin=h.absolutePos(BlockPos.ZERO).offset(2048,-3,0);
        for(int x=(origin.getX()>>4)-1;x<=(origin.getX()>>4)+1;x++)for(int z=(origin.getZ()>>4)-1;z<=(origin.getZ()>>4)+1;z++)l.setChunkForced(x,z,true);
        for(int x=0;x<14;x++)for(int z=0;z<12;z++){l.setBlockAndUpdate(origin.offset(x,0,z),Blocks.STONE.defaultBlockState());for(int y=1;y<7;y++)l.setBlockAndUpdate(origin.offset(x,y,z),Blocks.AIR.defaultBlockState());}
        var target=origin.offset(5,1,5);var store=origin.offset(8,1,5);l.setBlockAndUpdate(target,Blocks.STONE.defaultBlockState());l.setBlockAndUpdate(store,Blocks.BARREL.defaultBlockState());
        var barrel=(Container)l.getBlockEntity(store);boolean cargo=phase().equals("cargo");
        if(cargo)for(int i=0;i<barrel.getContainerSize();i++)barrel.setItem(i,new ItemStack(Items.DIRT,64)); // initial full original destination
        var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);
        n.teleportTo(origin.getX()+2.5,origin.getY()+1,origin.getZ()+5.5);n.setOnGround(true);n.setPersistenceRequired();n.setProfession(NpcProfession.WORKER);
        var p=WarMusterGameTests.battlePlayer(h,"CivilLord",new BlockPos(2,1,8));p.setPos(n.getX(),n.getY(),n.getZ()+3);
        var actor=CharacterRegistry.currentId(p);var life=NpcLifeData.get(s);life.onRecruited(n,actor,l.getGameTime());
        n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);
        n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));n.getPersonalInventory().setItem(0,new ItemStack(Items.IRON_SWORD));n.getPersonalInventory().setItem(1,new ItemStack(Items.BREAD,16));p.getInventory().setItem(3,new ItemStack(Items.EMERALD,16));
        life.setHunger(n.getCharacterId(),300,l.getGameTime());life.updateFatigue(n.getCharacterId(),0);life.setShift(n.getCharacterId(),NpcWorkShift.DAY,l.getGameTime());
        h.assertTrue(life.assignPrivateStorage(s,n.getCharacterId(),l.dimension().identifier().toString(),store,l.getGameTime()),"original private storage assignment");
        h.assertTrue(life.assignEmployerWorkZone(n,actor,target,target),"original employer work zone assignment");
        var mode=phase().equals("legacy")?NpcBehaviorMode.LEGACY:NpcBehaviorMode.PLANNED;NpcBrainRuntime.setMode(n,mode);n.setCommand(NpcCommand.WORK);
        h.assertTrue(WarMusterGameTests.command(p,"saga war muster create CivilArmy")==1,"ordinary army creation");
        var runtime=WarMusterRuntime.existing(s).orElseThrow();var army=WarMusterEntry.armyId(actor,"CivilArmy");var unit=WarMusterEntry.unitId(army,"Workers");UUID[] contract={null};
        h.onEachTick(()->{if(contract[0]!=null&&runtime.book().offer(contract[0]).filter(o->o.stage()==WarMusterEntry.Stage.PREPARING).isPresent())p.setPos(n.getX(),n.getY(),n.getZ()+3);});
        var sequence=h.startSequence().thenWaitUntil(()->{
            h.assertTrue(count(barrel,n,Items.COBBLESTONE)==1&&l.getBlockState(target).isAir(),"actual original mining works before enlistment; "+n.getWorkActivity()+" / "+n.getWorkActivityDetail());
            if(mode==NpcBehaviorMode.PLANNED&&!cargo)h.assertTrue(count(barrel,Items.COBBLESTONE)==1,"original storage discovery delivers the actual product");
            if(mode==NpcBehaviorMode.PLANNED)h.assertTrue(toolDamage(n)==1,"one actual original mining tool wear");
            h.assertTrue(runtime.book().owns(actor,army),"army ownership durable");
        }).thenExecute(()->h.assertTrue(WarMusterGameTests.command(p,"saga war muster unit CivilArmy Workers sword")==1,"ordinary sword unit"))
          .thenWaitUntil(()->h.assertTrue(runtime.book().unit(unit).filter(u->u.recorded()&&runtime.book().durable(u)).isPresent(),"unit durable"))
          .thenExecute(()->contract[0]=WarMusterGameTests.battleOffer(h,p,n,"CivilArmy","Workers"));
        if(cargo)sequence.thenWaitUntil(()->h.assertTrue(runtime.book().offer(contract[0]).filter(o->o.stage()==WarMusterEntry.Stage.PREPARING&&runtime.book().durable(o)).isPresent(),"accepted decision waits for original cargo"))
          .thenIdle(160).thenExecute(()->{
            h.assertTrue(runtime.book().offer(contract[0]).orElseThrow().stage()==WarMusterEntry.Stage.PREPARING&&!WorkCommitmentRuntime.blocksCivil(n),"no military labor claim while original delivery is outstanding: "+runtime.status(actor));
            h.assertTrue(n.getPersonalInventory().count(i->i.is(Items.COBBLESTONE))==1&&FieldworkData.get(s).pendingCargo(n.getUUID()).values().stream().mapToInt(Integer::intValue).sum()==1,"same physical cargo and original responsibility retained");
            barrel.setItem(0,ItemStack.EMPTY); // release capacity; original delivery executor must do the transfer
          });
        sequence.thenWaitUntil(()->{
            h.assertTrue(runtime.book().offer(contract[0]).filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE&&runtime.book().durable(o)).isPresent(),"military active: "+runtime.status(actor));
            if(cargo)h.assertTrue(count(barrel,Items.COBBLESTONE)==1&&FieldworkData.get(s).pendingCargo(n.getUUID()).isEmpty(),"original physical delivery finishes before military labor is claimed");
          }).thenExecute(()->{
            var at=origin.offset(4,1,7);var map=MapItem.create(l,at.getX(),at.getZ(),(byte)0,true,true);p.setItemSlot(EquipmentSlot.MAINHAND,map);Arrays.fill(MapItem.getSavedData(map,l).colors,(byte)40);
            var maps=WarMapRuntime.get(s);var v=maps.begin(p).view().orElseThrow();
            var preview=maps.act(p,new Request(v.session(),v.revision(),Action.PREVIEW,0,unit,WarMapView.Mode.HOLD,(int)Math.round(v.sheet().pixelX(at.getX())),(int)Math.round(v.sheet().pixelZ(at.getZ())),new UUID(0,0))).view().orElseThrow();
            h.assertTrue(preview.preview().isPresent(),"ordinary physical map preview");
            var receipt=maps.act(p,new Request(preview.session(),preview.revision(),Action.ISSUE,0,unit,WarMapView.Mode.HOLD,0,0,preview.preview().orElseThrow().ticket())).view().orElseThrow().receipt().orElseThrow();
            h.assertTrue(receipt.outcome()==WarMapView.Outcome.ACCEPTED,"ordinary physical map order");
          }).thenWaitUntil(()->{
            h.assertTrue(n.getMainHandItem().is(Items.IRON_SWORD)&&n.getPersonalInventory().count(i->i.is(Items.IRON_PICKAXE))==1,"actual witnessed military arming stows the original work tool; "+n.getWorkActivityDetail());
            h.assertTrue(WarServerEvents.existing(s).orElseThrow().field().orElseThrow().reports(actor,army).stream().anyMatch(r->r.unit().equals(unit)&&r.state()==WarFieldReport.State.HOLDING),"original body physically reaches the actual map station");
          })
          .thenExecute(()->l.setBlockAndUpdate(target,Blocks.STONE.defaultBlockState())).thenIdle(240)
          .thenExecute(()->{
            h.assertTrue(WorkCommitmentRuntime.blocksCivil(n)&&l.getBlockState(target).is(Blocks.STONE)&&count(barrel,n,Items.COBBLESTONE)==1,"military shared labor prevents simultaneous civil production");
          });
        if(phase().equals("save")){
            sequence.thenWaitUntil(()->{
                var provisions=WarServerEvents.existing(s).orElseThrow().nativeMobilization().orElseThrow().provisions();
                h.assertTrue(provisions.find(contract[0]).filter(v->v.pending().isEmpty()&&provisions.durable(contract[0])).isPresent(),"original shared provisions durable before process stop");
            }).thenExecute(()->{
                var facts=facts(h,n,p,barrel,target,store,mode,contract[0]);facts.put("origin",List.of(origin.getX(),origin.getY(),origin.getZ()));facts.put("stage","ACTIVE");
                write(s,new Gson().toJsonTree(facts).getAsJsonObject());
            }).thenSucceed();return;
        }
        finish(h,n,p,barrel,target,store,mode,()->contract[0],sequence,null);
    }
    private static void resume(GameTestHelper h){
        var l=h.getLevel();var s=l.getServer();var saved=read(s);
        h.assertTrue(saved.get("process").getAsLong()!=ProcessHandle.current().pid(),"another actual JVM required");
        var origin=position(saved,"origin");for(int x=(origin.getX()>>4)-1;x<=(origin.getX()>>4)+1;x++)for(int z=(origin.getZ()>>4)-1;z<=(origin.getZ()>>4)+1;z++)l.setChunkForced(x,z,true);
        var p=WarMusterGameTests.restoreBattlePlayer(h,new GameProfile(uuid(saved,"account"),saved.get("name").getAsString()),origin.offset(2,1,8));
        var actor=uuid(saved,"actor");var contract=uuid(saved,"contract");var target=position(saved,"target");var store=position(saved,"store");NpcBase[] n={null};Container[] barrel={null};
        var runtime=WarMusterRuntime.existing(s).orElseThrow();
        h.startSequence().thenWaitUntil(()->{
            h.assertTrue(l.getEntity(uuid(saved,"body")) instanceof NpcBase,"same saved original production body loads");n[0]=(NpcBase)l.getEntity(uuid(saved,"body"));barrel[0]=(Container)l.getBlockEntity(store);
            var offer=runtime.book().offer(contract).orElseThrow();
            h.assertTrue(actor.equals(CharacterRegistry.currentId(p))&&n[0].getCharacterId().equals(uuid(saved,"person"))&&offer.body().equals(n[0].getUUID()),"same original person, body, account and current life");
            h.assertTrue(offer.stage()==WarMusterEntry.Stage.ACTIVE&&offer.draw()==saved.get("draw").getAsDouble()&&offer.probability()==saved.get("probability").getAsDouble(),"original decision without resampling");
            h.assertTrue(WorkCommitmentRuntime.blocksCivil(n[0])&&count(barrel[0],n[0],Items.COBBLESTONE)==1&&toolDamage(n[0])==1,"original product, tool wear and military commitment survive process restart");
        }).thenIdle(160).thenExecute(()->{
            h.assertTrue(l.getBlockState(target).is(Blocks.STONE)&&count(barrel[0],n[0],Items.COBBLESTONE)==1&&toolDamage(n[0])==1,"no off-process or concurrent mining on restart");
            finish(h,n[0],p,barrel[0],target,store,NpcBehaviorMode.PLANNED,()->contract,h.startSequence(),saved);
        });
        // The completion sequence above owns success; do not end the test at this boundary.
    }
    private static void finish(GameTestHelper h,NpcBase n,ServerPlayer p,Container barrel,BlockPos target,BlockPos store,NpcBehaviorMode mode,java.util.function.Supplier<UUID> contract,GameTestSequence sequence,JsonObject saved){
        var l=h.getLevel();var s=l.getServer();var runtime=WarMusterRuntime.existing(s).orElseThrow();int[] cash={0,0};
        sequence.thenExecute(()->h.assertTrue(WarMusterGameTests.command(p,"saga war muster retire "+contract.get())==1,"ordinary exact-service retirement"))
          .thenWaitUntil(()->h.assertTrue(runtime.book().offer(contract.get()).filter(o->o.stage()==WarMusterEntry.Stage.ENDED&&runtime.book().durable(o)).isPresent()&&!WorkCommitmentRuntime.blocksCivil(n),"military labor durably released"))
          .thenWaitUntil(()->{
            h.assertTrue(count(barrel,n,Items.COBBLESTONE)==2&&l.getBlockState(target).isAir(),"actual mining product resumes after retirement; hand="+n.getMainHandItem()+" bagCobble="+n.getPersonalInventory().count(i->i.is(Items.COBBLESTONE))+" work="+n.getWorkActivity()+" / "+n.getWorkActivityDetail());
            if(mode==NpcBehaviorMode.PLANNED)h.assertTrue(count(barrel,Items.COBBLESTONE)==2,"second actual product delivered through original private storage");
            if(mode==NpcBehaviorMode.PLANNED)h.assertTrue(toolDamage(n)==2&&FieldworkData.get(s).receiptsFor(n.getUUID()).size()==2,"exactly two actual mining receipts and tool wears");
            var provisions=WarServerEvents.existing(s).orElseThrow().nativeMobilization().orElseThrow().provisions();var cost=provisions.find(contract.get()).orElseThrow();
            h.assertTrue(cost.pending().isEmpty()&&provisions.durable(contract.get())&&cost.marks().containsKey(ServiceProvision.Step.CLOSE_PAYROLL)&&cost.arrears(l.getGameTime()).signum()==0,"funded original closing payroll durably acknowledged");
          }).thenExecute(()->{
            h.assertTrue(n.getProfession()==NpcProfession.WORKER&&n.getCommand()==NpcCommand.WORK&&NpcBrainRuntime.mode(n)==mode&&target.equals(n.getWorkZoneMin())&&target.equals(n.getWorkZoneMax()),"original profession, command, mode and work zone retained");
            var life=NpcLifeData.get(s).record(n.getCharacterId());var offer=runtime.book().offer(contract.get()).orElseThrow();var parent=ServiceRelationshipData.get(s).relationship(offer.parent());
            h.assertTrue(store.equals(life.privateStoragePosition())&&life.authority()==NpcWorkAuthority.SERVICE&&life.authorityId().equals(CharacterRegistry.currentId(p))&&parent.activeAt(l.getGameTime())&&parent.revision()==offer.parentRevision(),"original private storage, civil authority and employment remain unchanged");
            h.assertTrue(p.getInventory().countItem(Items.EMERALD)+n.getPersonalInventory().count(i->i.is(Items.EMERALD))==16,"actual original cash conserved");
            cash[0]=p.getInventory().countItem(Items.EMERALD);cash[1]=n.getPersonalInventory().count(i->i.is(Items.EMERALD));
            h.assertTrue(WarMusterGameTests.command(p,"saga war muster retire "+contract.get())==1,"repeated retirement is a lookup");
          }).thenIdle(80).thenExecute(()->{
            h.assertTrue(p.getInventory().countItem(Items.EMERALD)==cash[0]&&n.getPersonalInventory().count(i->i.is(Items.EMERALD))==cash[1]&&count(barrel,n,Items.COBBLESTONE)==2,"no replayed payroll or products");
            var facts=facts(h,n,p,barrel,target,store,mode,contract.get());facts.put("stage","ENDED");facts.put("cargoHandoff",phase().equals("cargo"));facts.put("priorProcess",saved==null?null:saved.get("process").getAsLong());facts.put("originalCapture",saved);write(s,new Gson().toJsonTree(facts).getAsJsonObject());
          }).thenSucceed();
    }
    private static Map<String,Object> facts(GameTestHelper h,NpcBase n,ServerPlayer p,Container barrel,BlockPos target,BlockPos store,NpcBehaviorMode mode,UUID contract){
        var offer=WarMusterRuntime.existing(h.getLevel().getServer()).orElseThrow().book().offer(contract).orElseThrow();
        var f=new LinkedHashMap<String,Object>();f.put("process",ProcessHandle.current().pid());f.put("mode",mode);f.put("body",n.getUUID());f.put("person",n.getCharacterId());f.put("contract",contract);f.put("actor",CharacterRegistry.currentId(p));f.put("account",p.getUUID());f.put("name",p.getGameProfile().name());f.put("army",offer.request().armyId());f.put("unit",offer.request().unitId());f.put("draw",offer.draw());f.put("probability",offer.probability());f.put("target",List.of(target.getX(),target.getY(),target.getZ()));f.put("store",List.of(store.getX(),store.getY(),store.getZ()));f.put("products",count(barrel,n,Items.COBBLESTONE));f.put("delivered",count(barrel,Items.COBBLESTONE));f.put("held",n.getMainHandItem().toString());f.put("toolDamage",toolDamage(n));f.put("bread",n.getPersonalInventory().count(i->i.is(Items.BREAD)));f.put("payerEmeralds",p.getInventory().countItem(Items.EMERALD));f.put("npcEmeralds",n.getPersonalInventory().count(i->i.is(Items.EMERALD)));f.put("backgroundOnly",true);f.put("normalClientAccepted",false);return f;
    }
    private static int toolDamage(NpcBase n){if(n.getMainHandItem().is(Items.IRON_PICKAXE))return n.getMainHandItem().getDamageValue();for(int i=0;i<n.getPersonalInventory().getContainerSize();i++)if(n.getPersonalInventory().getItem(i).is(Items.IRON_PICKAXE))return n.getPersonalInventory().getItem(i).getDamageValue();throw new IllegalStateException("original pickaxe absent");}
    private static Path path(MinecraftServer s){return s.getWorldPath(LevelResource.ROOT).resolve("war-civil-proof.json");}
    private static JsonObject read(MinecraftServer s){try{return JsonParser.parseString(Files.readString(path(s))).getAsJsonObject();}catch(Exception e){throw new IllegalStateException("original civil recovery proof required",e);}}
    private static void write(MinecraftServer s,JsonObject value){try{Files.writeString(path(s),new GsonBuilder().setPrettyPrinting().create().toJson(value));}catch(Exception e){throw new IllegalStateException(e);}}
    private static UUID uuid(JsonObject p,String key){return UUID.fromString(p.get(key).getAsString());}
    private static BlockPos position(JsonObject p,String key){var a=p.getAsJsonArray(key);return new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());}
    private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private static int count(Container c,NpcBase n,Item item){return count(c,item)+n.getPersonalInventory().count(i->i.is(item));}
}
