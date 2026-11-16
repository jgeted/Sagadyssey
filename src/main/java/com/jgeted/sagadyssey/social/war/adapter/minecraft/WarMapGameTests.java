package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.combat.CombatEncounterData;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarMapNetwork12111.RequestPayload;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.util.*;

/** Real original NPCs and normal server map actions. Explicit army/consent/assets are fixture setup only. */
@GameTestHolder("sagadyssey_war_m4b2") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarMapGameTests {
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.m4b2.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.m4b2.phase","map");}
    @SubscribeEvent public static void started(ServerStartedEvent e){if(enabled()&&e.getServer() instanceof GameTestServer)try{var l=e.getServer().overworld();l.getStructureManager().getOrCreate(net.minecraft.resources.Identifier.parse("sagadyssey_war_m4b2:empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[48,4,32],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));}catch(Exception x){throw new IllegalStateException(x);}}
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_m4b2",phase(),"sagadyssey_war_m4b2:empty",4000,0,true,phase().equals("empty")?WarMapGameTests::empty:WarMapGameTests::map)):List.of();}
    private static void empty(GameTestHelper h){
        var p=player(h,"MapOnly",new BlockPos(4,1,4));var s=h.getLevel().getServer();p.setItemSlot(EquipmentSlot.MAINHAND,MapItem.create(h.getLevel(),p.getBlockX(),p.getBlockZ(),(byte)0,true,true));
        h.assertTrue(WarServerEvents.existing(s).isEmpty(),"query fixture starts without military state");var r=WarMapRuntime.get(s);var first=r.begin(p);var next=r.act(p,request(first,Action.REFRESH,0,UUID.randomUUID(),64,64,Optional.empty()));
        h.assertTrue(next.view().orElseThrow().totalUnits()==0&&WarServerEvents.existing(s).isEmpty(),"opening and refreshing empty map creates no army, authority or journal");
        var denied=r.act(p,request(next,Action.PREVIEW,0,UUID.randomUUID(),64,64,Optional.empty()));h.assertTrue(denied.view().orElseThrow().preview().isEmpty()&&WarServerEvents.existing(s).isEmpty(),"a forged preview also cannot create a military runtime");
        try(var files=Files.list(s.getWorldPath(LevelResource.ROOT).resolve("data"))){h.assertTrue(files.noneMatch(f->f.getFileName().toString().startsWith("sagadyssey_war")),"no military files after map queries");}catch(java.nio.file.NoSuchFileException absent){}catch(Exception e){throw new IllegalStateException(e);}
        proof(h,"empty",Map.of("noArmyCreated",true,"emptyPreviewDenied",true));h.succeed();
    }
    private static void map(GameTestHelper h){
        var l=h.getLevel();var server=l.getServer();for(int x=0;x<48;x++)for(int z=0;z<24;z++){for(int y=1;y<=12;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);h.setBlock(new BlockPos(x,0,z),Blocks.STONE);}
        var p=player(h,"MapCommander",new BlockPos(7,1,6));var stranger=player(h,"NoMapAuthority",new BlockPos(4,1,14));var actor=CharacterRegistry.currentId(p);var f=WarCandidateGameTests.fixture(h,3,actor);var original=f.requests().getFirst();
        var loadout=new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SWORD,false,false).id();var requests=f.requests().stream().map(r->new WarMilitiaRequest(r.id(),r.actorId(),r.armyId(),r.unitId(),r.characterId(),loadout,1,r.kind(),r.role(),r.wage(),r.requestedAtTick())).toList();var first=requests.getFirst();
        boolean[] authorized={true};var core=WarServerEvents.activate(server);core.bindAuthority((a,b)->a.equals(actor)&&b.equals(first.armyId())?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED);
        h.assertTrue(core.submit(new WarCommand.CreateUnit(UUID.randomUUID(),actor,first.armyId(),first.unitId(),loadout,1)).status()==WarResult.Status.APPLIED,"fixture army uses original core");
        for(int i=0;i<6;i++)h.assertTrue(core.submit(new WarCommand.CreateUnit(UUID.randomUUID(),actor,first.armyId(),UUID.randomUUID(),loadout,1)).status()==WarResult.Status.APPLIED,"bounded extra units for pagination");
        core.bindAuthority((a,b)->authorized[0]&&a.equals(actor)&&b.equals(first.armyId())?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED);
        h.setBlock(new BlockPos(3,1,20),Blocks.CHEST);h.setBlock(new BlockPos(6,1,20),Blocks.CHEST);var a=(ChestBlockEntity)l.getBlockEntity(h.absolutePos(new BlockPos(3,1,20)));var t=(ChestBlockEntity)l.getBlockEntity(h.absolutePos(new BlockPos(6,1,20)));for(int i=0;i<3;i++)a.setItem(i,new ItemStack(Items.IRON_SWORD));t.setItem(0,new ItemStack(Items.EMERALD,64));
        var armory=ItemLocation.block(UUID.randomUUID(),l.dimension().identifier().toString(),a.getBlockPos());var treasury=ItemLocation.block(UUID.randomUUID(),l.dimension().identifier().toString(),t.getBlockPos());armory.bindBlock(server);treasury.bindBlock(server);var rights=RightsStateData.get(server);var owner=SubjectRef.character(actor);rights.ensureContext(armory.id(),"test:armory",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(treasury.id(),"test:treasury",owner,owner,AccessPolicy.PRIVATE);
        for(int i=0;i<3;i++){var n=f.npcs().get(i);var spawn=h.absolutePos(new BlockPos(3+i,1,6));n.teleportTo(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);n.setNoGravity(false);n.setOnGround(true);n.setCommand(NpcCommand.STAY);n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,8));n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));f.sources().life().setHunger(n.getCharacterId(),400,l.getGameTime());var subject=SubjectRef.character(n.getCharacterId());rights.ensureContext(ItemLocation.personal(n.getCharacterId(),n.getUUID()).id(),"test:bag",subject,subject,AccessPolicy.PRIVATE);rights.ensureContext(ItemLocation.equipment(n.getCharacterId(),n.getUUID()).id(),"test:kit",subject,subject,AccessPolicy.PRIVATE);}
        server.overworld().getDataStorage().saveAndJoin();
        try{
            var ports=core.bindNativeMobilization(f.sources());var field=core.bindFieldExecution(new WarFieldRuntime.Sources(f.sources().characters(),f.sources().injuries(),CombatEncounterData.get(server),f.sources().life()));var runtime=WarMapRuntime.get(server);
            var goal=h.absolutePos(new BlockPos(30,1,12));var map=MapItem.create(l,goal.getX(),goal.getZ(),(byte)0,true,true);p.setItemSlot(EquipmentSlot.MAINHAND,map);var sheet=WarMapRuntime.sheet(p).orElseThrow();int px=(int)Math.floor(sheet.pixelX(goal.getX())),pz=(int)Math.floor(sheet.pixelZ(goal.getZ()));MapItem.getSavedData(map,l).colors[pz*128+px]=40;
            Update[] view={null};Request[] commit={null};UUID[] order={null};long[] before={0};
            h.startSequence().thenWaitUntil(()->{for(var r:requests)h.assertTrue(ports.configure(r,treasury,armory,List.of(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_SWORD)),1)))==com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.Status.APPLIED,"durable native kit/food/pay plan");})
              .thenWaitUntil(()->{for(var r:requests)h.assertTrue(core.mobilization().orElseThrow().preview(r).ready(),"native preview before begin: "+core.mobilization().orElseThrow().preview(r));})
              .thenExecute(()->{for(var r:requests){var result=core.mobilization().orElseThrow().begin(r);h.assertTrue(result.outcome()==WarMobilizationService.Outcome.ACCEPTED,"real original people mobilize: "+result);}})
              .thenWaitUntil(()->h.assertTrue(field.reports(actor,first.armyId()).size()==3,"three original own reports: "+core.mobilization().orElseThrow().service().orElseThrow().records().stream().map(r->r.stage()+":"+r.reviewReason()).toList()))
              .thenExecute(()->{
                  h.assertTrue(command(p,"saga war map")==1,"real Brigadier map entry");view[0]=runtime.begin(p);var firstView=view[0].view().orElseThrow();h.assertTrue(firstView.totalUnits()==7&&firstView.units().size()==6,"bounded authorized first page");
                  var tagBefore=f.ledgers().stream().map(d->d.save(new net.minecraft.nbt.CompoundTag(),l.registryAccess())).toList();var read=runtime.current(p,firstView.session(),0);h.assertTrue(tagBefore.equals(f.ledgers().stream().map(d->d.save(new net.minecraft.nbt.CompoundTag(),l.registryAccess())).toList()),"projection does not mutate shared facts");
                  var other=runtime.begin(strangerWithMap(stranger,l));h.assertTrue(other.view().orElseThrow().units().isEmpty(),"operator status does not reveal another character's units");
                  h.assertTrue(runtime.act(stranger,request(view[0],Action.REFRESH,0,first.unitId(),0,0,Optional.empty())).view().isEmpty(),"stolen session rejected without unit information");
                  view[0]=runtime.act(p,request(view[0],Action.REFRESH,1,first.unitId(),0,0,Optional.empty()));h.assertTrue(view[0].view().orElseThrow().units().size()==1,"second page has the remaining unit");
                  view[0]=runtime.act(p,request(view[0],Action.REFRESH,0,first.unitId(),0,0,Optional.empty()));before[0]=core.session().orElseThrow().unit(first.unitId()).orElseThrow().lastIssuedSequence();
                  view[0]=runtime.act(p,request(view[0],Action.PREVIEW,0,first.unitId(),px,pz,Optional.empty()));h.assertTrue(view[0].view().orElseThrow().preview().isPresent()&&core.session().orElseThrow().unit(first.unitId()).orElseThrow().lastIssuedSequence()==before[0],"preview is not a command");
                  commit[0]=request(view[0],Action.ISSUE,0,first.unitId(),0,0,Optional.of(view[0].view().orElseThrow().preview().orElseThrow().ticket()));view[0]=runtime.act(p,commit[0]);var receipt=view[0].view().orElseThrow().receipt().orElseThrow();h.assertTrue(receipt.outcome()==WarMapView.Outcome.ACCEPTED,"native map confirmation accepted");order[0]=receipt.order().orElseThrow();
                  var repeated=runtime.act(p,commit[0]);h.assertTrue(repeated.view().orElseThrow().receipt().orElseThrow().equals(receipt)&&core.session().orElseThrow().unit(first.unitId()).orElseThrow().lastIssuedSequence()==before[0]+1,"repeated confirmation returns one original receipt, not a second order");
              }).thenWaitUntil(()->h.assertTrue(field.reports(actor,first.armyId()).size()==3&&field.reports(actor,first.armyId()).stream().allMatch(r->r.order().filter(order[0]::equals).isPresent()&&r.state()==WarFieldReport.State.HOLDING),"all three actual original people receive the map order and physically hold their formation: "+field.reports(actor,first.armyId())+" target="+core.session().orElseThrow().unit(first.unitId()).orElseThrow().currentOrder()))
              .thenExecute(()->{
                  view[0]=runtime.act(p,request(view[0],Action.REFRESH,0,first.unitId(),0,0,Optional.empty()));int blank=(px+2)%128;MapItem.getSavedData(map,l).colors[pz*128+blank]=0;
                  view[0]=runtime.act(p,request(view[0],Action.PREVIEW,0,first.unitId(),blank,pz,Optional.empty()));h.assertTrue(view[0].view().orElseThrow().preview().isEmpty()&&view[0].message().contains("侦察"),"unexplored map pixel denied");
                  view[0]=runtime.act(p,request(view[0],Action.PREVIEW,0,first.unitId(),px,pz,Optional.empty()));h.assertTrue(view[0].view().orElseThrow().preview().isPresent(),"fresh second preview");
              }).thenIdle(625).thenExecute(()->{
                  view[0]=runtime.act(p,request(view[0],Action.ISSUE,0,first.unitId(),0,0,Optional.of(view[0].view().orElseThrow().preview().orElseThrow().ticket())));h.assertTrue(view[0].view().orElseThrow().receipt().orElseThrow().outcome()==WarMapView.Outcome.REJECTED&&core.session().orElseThrow().unit(first.unitId()).orElseThrow().lastIssuedSequence()==before[0]+1,"expired preview cannot issue");
                  view[0]=runtime.act(p,commit[0]);h.assertTrue(view[0].view().orElseThrow().receipt().orElseThrow().outcome()==WarMapView.Outcome.ACCEPTED&&view[0].view().orElseThrow().receipt().orElseThrow().order().filter(order[0]::equals).isPresent()&&core.session().orElseThrow().unit(first.unitId()).orElseThrow().lastIssuedSequence()==before[0]+1,"prior original receipt remains replay-free after another attempt was rejected");
                  view[0]=runtime.act(p,request(view[0],Action.PREVIEW,0,first.unitId(),px,pz,Optional.empty()));var ticket=view[0].view().orElseThrow().preview().orElseThrow().ticket();authorized[0]=false;view[0]=runtime.act(p,request(view[0],Action.ISSUE,0,first.unitId(),0,0,Optional.of(ticket)));h.assertTrue(view[0].view().orElseThrow().units().isEmpty()&&view[0].view().orElseThrow().receipt().orElseThrow().outcome()==WarMapView.Outcome.REJECTED,"revocation after preview denies submit and removes projection");authorized[0]=true;
                  var old=view[0];var wide=MapItem.create(l,p.getBlockX(),p.getBlockZ(),(byte)4,true,true);p.setItemSlot(EquipmentSlot.MAINHAND,wide);h.assertTrue(runtime.act(p,request(old,Action.REFRESH,0,first.unitId(),0,0,Optional.empty())).view().isEmpty(),"changing actual held map invalidates old session");view[0]=runtime.begin(p);var ws=WarMapRuntime.sheet(p).orElseThrow();MapItem.getSavedData(wide,l).colors[0]=40;
                  h.assertTrue(l.getChunkSource().getChunkNow(ws.x(0)>>4,ws.z(0)>>4)==null,"selected distant map pixel starts unloaded");view[0]=runtime.act(p,request(view[0],Action.PREVIEW,0,first.unitId(),0,0,Optional.empty()));h.assertTrue(view[0].message().contains("未加载")&&l.getChunkSource().getChunkNow(ws.x(0)>>4,ws.z(0)>>4)==null,"map preview cannot force-load a target chunk");
                  for(var r:requests)core.mobilization().orElseThrow().demobilize(r.id(),actor);
              }).thenWaitUntil(()->h.assertTrue(requests.stream().allMatch(r->core.mobilization().orElseThrow().service().orElseThrow().find(r.id()).orElseThrow().stage()==WarMobilization.Stage.RELEASED),"original services really released"))
              .thenExecute(()->{h.assertTrue(a.countItem(Items.IRON_SWORD)==3&&f.npcs().stream().allMatch(n->!n.isNoAi()&&n.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE)),"all borrowed swords returned and private tools preserved");proof(h,"map",Map.of("people",3,"authorizedUnits",7,"pageLimit",6,"previewOnly",true,"singleDispatch",true,"realPhysicalHold",true,"unknownAndUnloadedDenied",true,"revocationDenied",true,"privateToolsPreserved",true,"stage","RELEASED"));}).thenSucceed();
        }catch(Exception e){throw new IllegalStateException("native map fixture failed",e);}
    }
    private static Request request(Update update,Action action,int page,UUID unit,int x,int z,Optional<UUID> ticket){var v=update.view().orElseThrow();var r=new Request(v.session(),v.revision(),action,page,unit,WarMapView.Mode.HOLD,x,z,ticket.orElse(new UUID(0,0)));var b=io.netty.buffer.Unpooled.buffer();try{RequestPayload.CODEC.encode(b,new RequestPayload(r));return RequestPayload.CODEC.decode(b).value();}finally{b.release();}}
    private static ServerPlayer strangerWithMap(ServerPlayer p,net.minecraft.server.level.ServerLevel l){p.setItemSlot(EquipmentSlot.MAINHAND,MapItem.create(l,p.getBlockX(),p.getBlockZ(),(byte)0,true,true));return p;}
    private static ServerPlayer player(GameTestHelper h,String name,BlockPos pos){var p=FakePlayerFactory.get(h.getLevel(),new GameProfile(UUID.randomUUID(),name));var at=h.absolutePos(pos);p.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);p.setNoGravity(true);p.setGameMode(GameType.SURVIVAL);h.getLevel().addNewPlayer(p);var c=CharacterRegistry.ensureCurrent(p);CharacterRegistryData.get(h.getLevel().getServer()).bindNpcBody(c.id(),h.getLevel().dimension().identifier().toString(),p.getUUID(),h.getLevel().getGameTime());return p;}
    private static int command(ServerPlayer p,String text){try{return p.level().getServer().getCommands().getDispatcher().execute(text,p.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ALL).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}}
    private static void proof(GameTestHelper h,String phase,Map<String,Object> values){var p=new JsonObject();values.forEach((k,v)->p.add(k,new Gson().toJsonTree(v)));p.addProperty("process",ProcessHandle.current().pid());try{Files.writeString(h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("war-m4b2-"+phase+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(p));}catch(Exception e){throw new IllegalStateException(e);}}
}
