package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.work.WorkCommitmentRuntime;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.level.BlockEvent;
import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;

@GameTestHolder("sagadyssey_war_engineering") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarEngineeringGameTests{
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.engineering.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.engineering.phase","build");}
    private static boolean digging(){return Set.of("dig","dig-protection","full","ditch","reuse").contains(phase());}
    private static boolean restoring(){return Set.of("resume","missing","missing-receipt","orphan","issue-resume").contains(phase());}
    private static boolean saving(){return Set.of("save","issue-save").contains(phase());}
    private static boolean deny,denied;private static UUID ownerAccount;
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void starting(ServerAboutToStartEvent e)throws Exception{
        if(enabled()&&Set.of("missing","missing-receipt","orphan").contains(phase()))for(var name:phase().equals("orphan")?List.of(WarEngineeringRuntime.FILE,"sagadyssey_fieldwork_v3.dat"):List.of(phase().equals("missing")?WarEngineeringRuntime.FILE:"sagadyssey_fieldwork_v3.dat")){var p=e.getServer().getWorldPath(LevelResource.ROOT).resolve("data/"+name);Files.move(p,p.resolveSibling(p.getFileName()+".test-retained"));}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent e)throws Exception{
        if(!enabled()||!(e.getServer() instanceof GameTestServer))return;var s=e.getServer();var l=s.overworld();
        if(restoring()){var p=read(s.getWorldPath(LevelResource.ROOT).resolve("war-engineering-save.json"));s.getWorldData().overworldData().setGameTime(p.get("gameTime").getAsLong());s.getWorldData().overworldData().setDayTime(p.get("dayTime").getAsLong());}
        l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME,false,s);l.getGameRules().set(GameRules.SPAWN_MOBS,false,s);l.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_engineering:empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void protection(BlockEvent.EntityPlaceEvent e){if(enabled()&&deny&&e.getEntity()!=null&&e.getEntity().getUUID().equals(ownerAccount)){denied=true;e.setCanceled(true);}}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void protection(BlockEvent.BreakEvent e){if(enabled()&&deny&&e.getPlayer().getUUID().equals(ownerAccount)){denied=true;e.setCanceled(true);}}
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_engineering",phase(),"sagadyssey_war_engineering:empty",9000,0,true,restoring()?WarEngineeringGameTests::resume:WarEngineeringGameTests::run)):List.of();}
    private record Scene(GameTestHelper h,BlockPos origin,ServerPlayer lord,List<NpcBase> people,List<UUID> services,UUID army,List<UUID> jobs,ChestBlockEntity stock){}
    private static void run(GameTestHelper h){try{
        var s=h.getLevel().getServer();var l=h.getLevel();var o=h.absolutePos(BlockPos.ZERO).offset(3072,-3,0);force(h,o);for(int x=0;x<56;x++)for(int z=0;z<32;z++){l.setBlockAndUpdate(o.offset(x,0,z),Blocks.STONE.defaultBlockState());for(int y=1;y<5;y++)l.setBlockAndUpdate(o.offset(x,y,z),Blocks.AIR.defaultBlockState());}
        var p=WarMusterGameTests.battlePlayer(h,"EngineerLord",BlockPos.ZERO);ownerAccount=p.getUUID();p.getInventory().setItem(3,new ItemStack(Items.EMERALD,64));var people=new ArrayList<NpcBase>();people.add(person(h,p,o.offset(5,1,5),NpcProfession.WORKER));
        if(phase().equals("cooperate"))people.add(person(h,p,o.offset(5,1,13),NpcProfession.WORKER));if(phase().equals("supply"))people.add(person(h,p,o.offset(7,1,5),NpcProfession.COURIER));
        for(var n:people)n.getPersonalInventory().setItem(2,new ItemStack(Items.COBBLESTONE,phase().equals("supply")||digging()?0:12));
        if(digging()&&!phase().equals("ditch"))for(int x=14;x<17;x++)l.setBlockAndUpdate(o.offset(x,1,10),Blocks.STONE.defaultBlockState());
        if(phase().equals("ditch"))for(int x=14;x<17;x++)for(int z=10;z<12;z++)l.setBlockAndUpdate(o.offset(x,-1,z),Blocks.STONE.defaultBlockState());
        if(phase().equals("access"))for(int x=14;x<17;x++){l.setBlockAndUpdate(o.offset(x,0,10),Blocks.AIR.defaultBlockState());l.setBlockAndUpdate(o.offset(x,-2,10),Blocks.STONE.defaultBlockState());}
        if(phase().equals("full"))for(int i=2;i<people.getFirst().getPersonalInventory().getContainerSize();i++)people.getFirst().getPersonalInventory().setItem(i,new ItemStack(Items.DIRT,64));
        ChestBlockEntity stock=null;if(phase().equals("supply")){l.setBlockAndUpdate(o.offset(5,1,18),Blocks.CHEST.defaultBlockState());stock=(ChestBlockEntity)l.getBlockEntity(o.offset(5,1,18));stock.setItem(0,new ItemStack(Items.COBBLESTONE,6));stock.setItem(1,new ItemStack(Items.EMERALD,16));}
        var services=new ArrayList<UUID>();var jobs=new ArrayList<UUID>();command(h,p,"saga war muster create Works");var army=WarMusterEntry.armyId(CharacterRegistry.currentId(p),"Works");var unit=WarMusterEntry.unitId(army,"Crew");var scene=new Scene(h,o,p,people,services,army,jobs,stock);
        var q=h.startSequence().thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().owns(CharacterRegistry.currentId(p),army),"original army durable"))
            .thenExecute(()->command(h,p,"saga war muster unit Works Crew sword"))
            .thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().unit(unit).filter(u->u.recorded()&&WarMusterRuntime.existing(s).orElseThrow().book().durable(u)).isPresent(),"original unit durable"));
        if(scene.stock()!=null)q.thenExecute(()->{var at=scene.stock().getBlockPos();p.setPos(at.getX()+1.5,at.getY(),at.getZ()+.5);command(h,p,"saga war support depot Works "+xyz(at));}).thenWaitUntil(()->h.assertTrue(WarCampaignRuntime.existing(s).orElseThrow().depot(army).isPresent(),"original actual material depot durable"));
        for(int i=0;i<people.size();i++){int index=i;q.thenExecute(()->services.add(WarMusterGameTests.battleOffer(h,p,people.get(index),"Works","Crew",phase().equals("supply")&&index==1?WarMilitiaRequest.Role.STAFF:WarMilitiaRequest.Role.ENGINEER))).thenWaitUntil(()->h.assertTrue(services.size()>index&&active(scene,services.get(index)),"original consenting work body active"));}
        q.thenExecute(()->{deny=Set.of("protection","dig-protection").contains(phase());if(phase().equals("build"))preflightChecks(scene);approve(scene,0);if(phase().equals("cooperate"))approve(scene,1);});
        if(phase().equals("issue-save")){q.thenWaitUntil(()->h.assertTrue(job(scene,0).next()==0&&job(scene,0).phase()==WarEngineeringJob.Phase.ISSUING,"original intent exists before effect"))
            .thenExecute(()->people.getFirst().setNoAi(true)).thenWaitUntil(()->h.assertTrue(runtime(scene).durable(job(scene,0)),"unissued original intent is durable"))
            .thenExecute(()->{h.assertTrue(blocks(scene)==0&&people.getFirst().getPersonalInventory().countItem(Items.COBBLESTONE)==12,"stopped before physical mutation");capture(scene,"SAVED_UNISSUED_INTENT");}).thenSucceed();return;}
        if(phase().equals("save")){q.thenWaitUntil(()->h.assertTrue(job(scene,0).next()==1&&job(scene,0).phase()==WarEngineeringJob.Phase.ACTIVE&&runtime(scene).durable(job(scene,0)),"one original block and material receipt durable before save"))
            .thenExecute(()->capture(scene,"SAVED_ONE_BLOCK")).thenSucceed();return;}
        if(Set.of("cancel","retire").contains(phase())){
            q.thenWaitUntil(()->h.assertTrue(job(scene,0).next()==1&&job(scene,0).phase()==WarEngineeringJob.Phase.ACTIVE&&runtime(scene).durable(job(scene,0)),"first actual block before stopping work"))
                .thenExecute(()->command(h,p,phase().equals("retire")?"saga war muster retire "+services.getFirst():"saga war engineer pause "+jobs.getFirst()));
            if(phase().equals("cancel"))q.thenIdle(80).thenExecute(()->{h.assertTrue(job(scene,0).next()==1&&blocks(scene)==1&&people.getFirst().getPersonalInventory().countItem(Items.COBBLESTONE)==11,"pause retains one block and remaining original materials");command(h,p,"saga war engineer resume "+jobs.getFirst());})
                .thenWaitUntil(()->h.assertTrue(job(scene,0).next()==2&&job(scene,0).phase()==WarEngineeringJob.Phase.ACTIVE&&runtime(scene).durable(job(scene,0)),"resumed real second cell"))
                .thenExecute(()->command(h,p,"saga war engineer cancel "+jobs.getFirst()));
            q.thenWaitUntil(()->h.assertTrue(job(scene,0).phase()==WarEngineeringJob.Phase.CANCELLED&&runtime(scene).durable(job(scene,0)),"original work cancels without undoing built blocks"));
            close(q,scene,phase().equals("cancel")?2:1);return;
        }
        if(Set.of("protection","dig-protection").contains(phase()))q.thenWaitUntil(()->h.assertTrue(denied&&job(scene,0).phase()==WarEngineeringJob.Phase.ACTIVE&&runtime(scene).durable(job(scene,0)),"native protection rejection returned confirmed no effect"))
            .thenExecute(()->{h.assertTrue(blocks(scene)==0&&people.getFirst().getPersonalInventory().countItem(Items.COBBLESTONE)==(digging()?0:12)&&people.getFirst().getPersonalInventory().getItem(1).getDamageValue()==0&&FieldworkData.get(s).receiptsFor(people.getFirst().getUUID()).isEmpty(),"native cancelled work rolls block, material, tool, rights and original receipt back");deny=false;});
        if(phase().equals("full"))q.thenIdle(150).thenExecute(()->{h.assertTrue(job(scene,0).next()==0&&people.getFirst().getPersonalInventory().countItem(Items.COBBLESTONE)==0&&people.getFirst().getPersonalInventory().getItem(1).getDamageValue()==0,"full original inventory blocks destruction, outputs and tool wear");people.getFirst().getPersonalInventory().removeItemNoUpdate(people.getFirst().getPersonalInventory().getContainerSize()-1);});
        if(phase().equals("supply"))q.thenIdle(100).thenExecute(()->{h.assertTrue(job(scene,0).next()==0&&blocks(scene)==0,"missing real material never creates free blocks");command(h,p,"saga war support supply "+services.get(1)+" "+people.getFirst().getCharacterId()+" minecraft:cobblestone 6 1");});
        q.thenWaitUntil(()->h.assertTrue(jobs.stream().map(id->runtime(scene).find(id).orElseThrow()).anyMatch(j->j.phase()==WarEngineeringJob.Phase.DONE&&runtime(scene).durable(j)),"actual finite engineering completes: "+runtime(scene).status(p,army)+" bodies="+people.stream().map(n->n.position()+" navDone="+n.getNavigation().isDone()).toList()));
        if(phase().equals("reuse"))q.thenExecute(()->{h.assertTrue(FieldworkData.get(s).pendingCargo(people.getFirst().getUUID()).getOrDefault("minecraft:cobblestone#0",0)==3,"actual excavated stone remains original accounted cargo");approve(scene,0);})
            .thenWaitUntil(()->h.assertTrue(jobs.size()==2&&runtime(scene).find(jobs.get(1)).filter(j->j.phase()==WarEngineeringJob.Phase.DONE&&runtime(scene).durable(j)).isPresent(),"second approved construction reuses actual excavated material"));
        if(phase().equals("cooperate")){q.thenExecute(()->{var loser=jobs.stream().map(id->runtime(scene).find(id).orElseThrow()).filter(j->j.phase()!=WarEngineeringJob.Phase.DONE).findFirst().orElseThrow();h.assertTrue(loser.next()==0,"shared target lease prevents duplicate first cell");});
            for(int i=0;i<6;i++)q.thenExecute(()->{var loser=jobs.stream().map(id->runtime(scene).find(id).orElseThrow()).filter(j->j.phase()!=WarEngineeringJob.Phase.DONE).findFirst().orElseThrow();command(h,p,"saga war engineer adopt "+loser.id());}).thenWaitUntil(()->h.assertTrue(jobs.stream().allMatch(id->runtime(scene).durable(runtime(scene).find(id).orElseThrow())),"explicit external completion checkpoint"));
        }
        close(q,scene,phase().equals("reuse")?3:digging()||phase().equals("access")?0:phase().equals("platform")?3:6);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static void close(net.minecraft.gametest.framework.GameTestSequence q,Scene scene,int placed){
        q.thenExecute(()->{for(var id:scene.services())command(scene.h(),scene.lord(),"saga war muster retire "+id);});
        if(phase().equals("full"))q.thenIdle(80).thenExecute(()->{var n=scene.people().getFirst();scene.h().assertTrue(n.getPersonalInventory().countItem(Items.EMERALD)==0&&scene.lord().getInventory().countItem(Items.EMERALD)==64,"full real recipient retains unpaid original wage, no phantom cash transfer");n.getPersonalInventory().removeItemNoUpdate(n.getPersonalInventory().getContainerSize()-2);});
        q.thenWaitUntil(()->scene.h().assertTrue(scene.services().stream().allMatch(id->ended(scene,id))&&(scene.stock()==null?scene.lord().getInventory().countItem(Items.EMERALD)==64-scene.people().size():scene.stock().countItem(Items.EMERALD)==16-scene.people().size()),"original labor and actual base wages close once"))
            .thenExecute(()->{scene.h().assertTrue(blocks(scene)==placed,"actual placed block count");if(digging()){int count=phase().equals("ditch")?6:3;int remaining=phase().equals("reuse")?0:count;scene.h().assertTrue(scene.people().getFirst().getPersonalInventory().countItem(Items.COBBLESTONE)==remaining&&scene.people().getFirst().getPersonalInventory().getItem(1).getDamageValue()==count&&FieldworkData.get(scene.h().getLevel().getServer()).pendingCargo(scene.people().getFirst().getUUID()).getOrDefault("minecraft:cobblestone#0",0)==remaining,"original drops, actual tool wear and cargo responsibility agree after retirement");}verifyTerrain(scene);capture(scene,"COMPLETE");}).thenSucceed();
    }
    private static void approve(Scene f,int worker){boolean dig=digging()&&!(phase().equals("reuse")&&!f.jobs().isEmpty());var h=f.h();var a=f.origin().offset(14,phase().equals("ditch")||phase().equals("access")?0:1,10);var b=f.origin().offset(digging()||Set.of("platform","access").contains(phase())?16:19,phase().equals("wall")?2:a.getY()-f.origin().getY(),Set.of("ditch","platform").contains(phase())?11:10);command(h,f.lord(),"saga war engineer "+(dig?"dig ":"build ")+f.services().get(worker)+" "+xyz(a)+" "+xyz(b)+(dig?"":" minecraft:cobblestone"));var confirm=WarMusterGameTests.latestConfirmation(f.lord());command(h,f.lord(),confirm.substring(1));var id=runtime(f).entries().stream().filter(j->j.service().equals(f.services().get(worker))).max(Comparator.comparingLong(WarEngineeringJob::createdAt)).orElseThrow().id();f.jobs().add(id);}
    private static void preflightChecks(Scene f){
        var h=f.h();var p=f.lord();var service=f.services().getFirst();var l=h.getLevel();var a=f.origin().offset(14,1,10);var prefix="saga war engineer build "+service+" ";
        var stranger=WarMusterGameTests.battlePlayer(h,"EngineerOther",BlockPos.ZERO);h.assertTrue(WarMusterGameTests.command(stranger,prefix+xyz(a)+" "+xyz(a)+" minecraft:cobblestone")==0,"foreign operator has no military construction authority");
        h.assertTrue(WarMusterGameTests.command(p,prefix+xyz(a)+" "+xyz(a.offset(64,0,0))+" minecraft:cobblestone")==0,"65 cells rejected before world enumeration");
        h.assertTrue(WarMusterGameTests.command(p,prefix+xyz(a)+" "+xyz(a)+" minecraft:tnt")==0,"explosives never admitted as ordinary engineering material");
        var asset=f.origin().offset(24,1,10);l.setBlockAndUpdate(asset,Blocks.CHEST.defaultBlockState());((ChestBlockEntity)l.getBlockEntity(asset)).setItem(0,new ItemStack(Items.DIAMOND));
        h.assertTrue(WarMusterGameTests.command(p,"saga war engineer dig "+service+" "+xyz(asset)+" "+xyz(asset))==0&&((ChestBlockEntity)l.getBlockEntity(asset)).countItem(Items.DIAMOND)==1,"original asset and contents remain intact");
        command(h,p,prefix+xyz(a)+" "+xyz(a)+" minecraft:cobblestone");var confirm=WarMusterGameTests.latestConfirmation(p);l.setBlockAndUpdate(a,Blocks.DIRT.defaultBlockState());
        h.assertTrue(WarMusterGameTests.command(p,confirm.substring(1))==0&&runtime(f).entries().isEmpty(),"changed preview cannot approve stale geometry");l.setBlockAndUpdate(a,Blocks.AIR.defaultBlockState());
        h.assertTrue(f.people().getFirst().getPersonalInventory().countItem(Items.COBBLESTONE)==12,"rejected previews consume no original materials");
    }
    private static void verifyTerrain(Scene f){
        var l=f.h().getLevel();var o=f.origin();var n=f.people().getFirst();
        if(phase().equals("wall")){for(int x=14;x<20;x++)for(int y=1;y<3;y++)f.h().assertTrue(l.getBlockState(o.offset(x,y,10)).is(Blocks.COBBLESTONE),"two-high real wall");var from=net.minecraft.world.phys.Vec3.atCenterOf(o.offset(17,2,8));var to=net.minecraft.world.phys.Vec3.atCenterOf(o.offset(17,2,12));var hit=l.clip(new net.minecraft.world.level.ClipContext(from,to,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,n));f.h().assertTrue(hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK,"actual wall occludes native line of sight");}
        if(phase().equals("ditch"))for(int x=14;x<17;x++)for(int z=10;z<12;z++)f.h().assertTrue(l.getBlockState(o.offset(x,0,z)).isAir()&&l.getBlockState(o.offset(x,-1,z)).is(Blocks.STONE),"actual bounded ditch changes ground height");
        if(phase().equals("access"))for(int x=14;x<17;x++)f.h().assertTrue(l.getBlockState(o.offset(x,0,10)).isFaceSturdy(l,o.offset(x,0,10),Direction.UP),"actual filled walkway supports traversal");
        if(phase().equals("platform")){for(int x=14;x<17;x++)for(int z=10;z<12;z++)f.h().assertTrue(l.getBlockState(o.offset(x,1,z)).isFaceSturdy(l,o.offset(x,1,z),Direction.UP),"real raised platform support");var path=n.getNavigation().createPath(o.offset(15,2,10),0);f.h().assertTrue(path!=null&&path.canReach(),"native navigation reaches actual raised platform");}
    }
    private static void resume(GameTestHelper h){try{
        var s=h.getLevel().getServer();var root=s.getWorldPath(LevelResource.ROOT);var saved=read(root.resolve("war-engineering-save.json"));h.assertTrue(saved.get("process").getAsLong()!=ProcessHandle.current().pid(),"independent original-world JVM");var o=BlockPos.of(saved.get("origin").getAsLong());force(h,o);var p=WarMusterGameTests.restoreBattlePlayer(h,new GameProfile(UUID.fromString(saved.get("account").getAsString()),saved.get("name").getAsString()),o.offset(5,1,8));var body=UUID.fromString(saved.getAsJsonArray("bodies").get(0).getAsString());var services=new ArrayList<>(saved.getAsJsonArray("services").asList().stream().map(v->UUID.fromString(v.getAsString())).toList());var jobs=new ArrayList<>(saved.getAsJsonArray("jobs").asList().stream().map(v->UUID.fromString(v.getAsString())).toList());var people=new ArrayList<NpcBase>();var scene=new Scene(h,o,p,people,services,UUID.fromString(saved.get("army").getAsString()),jobs,null);
        var q=h.startSequence().thenWaitUntil(()->{var e=h.getLevel().getEntity(body);h.assertTrue(e instanceof NpcBase,"load same original engineer body");people.clear();people.add((NpcBase)e);h.assertTrue(active(scene,services.getFirst()),"same original active engineering service");});
        if(phase().equals("orphan"))q.thenWaitUntil(()->h.assertTrue(WarEngineeringRuntime.existing(s).filter(r->r.problem().contains("BODY_PROOF_WITHOUT_PLAN")).isPresent(),"co-saved original body exposes missing plan and receipt"));
        if(phase().equals("issue-resume")){q.thenExecute(()->people.getFirst().setNoAi(false)).thenWaitUntil(()->h.assertTrue(job(scene,0).phase()==WarEngineeringJob.Phase.REVIEW&&runtime(scene).durable(job(scene,0)),"cold ISSUING without original effect receipt requires review"))
            .thenExecute(()->command(h,p,"saga war muster retire "+services.getFirst())).thenIdle(80).thenExecute(()->{h.assertTrue(!ended(scene,services.getFirst())&&WorkCommitmentRuntime.blocksCivil(people.getFirst())&&blocks(scene)==0&&people.getFirst().getPersonalInventory().countItem(Items.COBBLESTONE)==12,"unknown intent never replays or releases original responsibility");capture(scene,"REVIEW");}).thenSucceed();return;}
        if(Set.of("missing","missing-receipt","orphan").contains(phase())){
            q.thenExecute(()->{boolean failed=false;try{var runtime=WarEngineeringRuntime.open(s);failed=phase().equals("orphan")&&runtime.problem().contains("BODY_PROOF_WITHOUT_PLAN");}catch(Exception expected){failed=true;}h.assertTrue(failed,"missing original engineering evidence blocks guessed recovery");command(h,p,"saga war muster retire "+services.getFirst());})
                .thenIdle(80).thenExecute(()->{h.assertTrue(!ended(scene,services.getFirst())&&WorkCommitmentRuntime.blocksCivil(people.getFirst())&&blocks(scene)==1&&people.getFirst().getPersonalInventory().countItem(Items.COBBLESTONE)==11,"unknown work preserves built block, original material and labor responsibility");if(Set.of("missing","orphan").contains(phase()))h.assertTrue(!Files.exists(root.resolve("data/"+WarEngineeringRuntime.FILE)),"no empty replacement journal");capture(scene,"REVIEW");}).thenSucceed();return;
        }
        q.thenWaitUntil(()->h.assertTrue(job(scene,0).phase()==WarEngineeringJob.Phase.DONE&&runtime(scene).durable(job(scene,0)),"same approved original job resumes without duplicated first material or block"));close(q,scene,6);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static NpcBase person(GameTestHelper h,ServerPlayer p,BlockPos at,NpcProfession profession){var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);n.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);n.setPersistenceRequired();n.setProfession(profession);n.setCommand(NpcCommand.STAY);n.setIdentity(NpcSocialClass.COMMONER,NpcOffice.NONE);for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));n.getPersonalInventory().clearContent();n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,16));n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));var life=NpcLifeData.get(h.getLevel().getServer());life.onRecruited(n,CharacterRegistry.currentId(p),h.getLevel().getGameTime());life.setHunger(n.getCharacterId(),100,h.getLevel().getGameTime());return n;}
    private static WarEngineeringRuntime runtime(Scene s){return WarEngineeringRuntime.existing(s.h().getLevel().getServer()).orElseThrow();}
    private static WarEngineeringJob job(Scene s,int i){return runtime(s).find(s.jobs().get(i)).orElseThrow();}
    private static boolean active(Scene f,UUID id){var r=WarMusterRuntime.existing(f.h().getLevel().getServer()).orElseThrow();return r.book().offer(id).filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE&&r.book().durable(o)).isPresent();}
    private static boolean ended(Scene f,UUID id){var r=WarMusterRuntime.existing(f.h().getLevel().getServer()).orElseThrow();return r.book().offer(id).filter(o->o.stage()==WarMusterEntry.Stage.ENDED&&r.book().durable(o)).isPresent();}
    private static int blocks(Scene f){int count=0;for(int x=14;x<20;x++)if(f.h().getLevel().getBlockState(f.origin().offset(x,1,10)).is(Blocks.COBBLESTONE))count++;return count;}
    private static String xyz(BlockPos p){return p.getX()+" "+p.getY()+" "+p.getZ();}
    private static void command(GameTestHelper h,ServerPlayer p,String text){h.assertTrue(WarMusterGameTests.command(p,text)==1,"ordinary command "+text+"; "+WarMusterGameTests.feedback(p));}
    private static void force(GameTestHelper h,BlockPos o){for(int x=o.getX()>>4;x<=(o.getX()+56)>>4;x++)for(int z=o.getZ()>>4;z<=(o.getZ()+32)>>4;z++)h.getLevel().setChunkForced(x,z,true);}
    private static void capture(Scene f,String stage){try{var p=new JsonObject();p.addProperty("process",ProcessHandle.current().pid());p.addProperty("phase",phase());p.addProperty("stage",stage);p.addProperty("origin",f.origin().asLong());p.addProperty("account",f.lord().getUUID().toString());p.addProperty("name",f.lord().getGameProfile().name());p.addProperty("actor",CharacterRegistry.currentId(f.lord()).toString());p.addProperty("army",f.army().toString());p.add("bodies",new Gson().toJsonTree(f.people().stream().map(NpcBase::getUUID).toList()));p.add("services",new Gson().toJsonTree(f.services()));p.add("jobs",new Gson().toJsonTree(f.jobs()));p.addProperty("placed",blocks(f));p.addProperty("playerCash",f.lord().getInventory().countItem(Items.EMERALD));p.add("npcCash",new Gson().toJsonTree(f.people().stream().map(n->n.getPersonalInventory().countItem(Items.EMERALD)).toList()));p.add("materials",new Gson().toJsonTree(f.people().stream().map(n->n.getPersonalInventory().countItem(Items.COBBLESTONE)).toList()));p.addProperty("headlessNoOpWire",true);if(f.stock()!=null){p.addProperty("stockCash",f.stock().countItem(Items.EMERALD));p.addProperty("stockMaterials",f.stock().countItem(Items.COBBLESTONE));}var root=f.h().getLevel().getServer().getWorldPath(LevelResource.ROOT);write(root.resolve("war-engineering-"+phase()+".json"),p);if(saving())write(root.resolve("war-engineering-save.json"),p);}catch(Exception e){throw new IllegalStateException(e);}}
    private static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    private static void write(Path p,JsonObject data)throws Exception{Files.writeString(p,new GsonBuilder().setPrettyPrinting().create().toJson(data));}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopped(ServerStoppedEvent e)throws Exception{if(enabled()&&saving()){var root=e.getServer().getWorldPath(LevelResource.ROOT);if(!Files.exists(root.resolve("war-engineering-save.json")))return;var p=read(root.resolve("war-engineering-save.json"));var data=NbtIo.readCompressed(root.resolve("level.dat"),NbtAccounter.create(64L*1024*1024)).getCompoundOrEmpty("Data");p.addProperty("gameTime",data.getLongOr("Time",0));p.addProperty("dayTime",data.getLongOr("DayTime",0));write(root.resolve("war-engineering-save.json"),p);}}
}
