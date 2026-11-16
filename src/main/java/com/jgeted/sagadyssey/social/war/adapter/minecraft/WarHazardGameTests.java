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
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
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

/** Background ordinary commands and original bodies. Fixture placement is distinct from production work. */
@GameTestHolder("sagadyssey_war_hazards") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarHazardGameTests{
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.hazards.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.hazards.phase","spike");}
    private static boolean saving(){return Set.of("save","issue-save").contains(phase());}
    private static boolean restoring(){return Set.of("resume","issue-resume").contains(phase());}
    private static boolean tunnel(){return Set.of("tunnel","bend","adopt","ore","water","asset","falling","save","issue-save","cancel","retire","full","blocked-route").contains(phase());}
    private static boolean deny,denied;private static UUID account;
    private static final class Scene{
        GameTestHelper h;BlockPos o;ServerPlayer p;NpcBase n;UUID army,service,job;final List<UUID> jobs=new ArrayList<>();
        final List<LivingEntity> victims=new ArrayList<>();final JsonObject physics=new JsonObject();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent e)throws Exception{
        if(!enabled()||!(e.getServer() instanceof GameTestServer))return;var s=e.getServer();var l=s.overworld();
        if(restoring()){var saved=read(s.getWorldPath(LevelResource.ROOT).resolve("war-hazards-save.json"));s.getWorldData().overworldData().setGameTime(saved.get("gameTime").getAsLong());s.getWorldData().overworldData().setDayTime(saved.get("dayTime").getAsLong());}
        l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME,false,s);l.getGameRules().set(GameRules.SPAWN_MOBS,false,s);
        l.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_hazards:empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void protection(BlockEvent.EntityPlaceEvent e){if(enabled()&&deny&&e.getEntity()!=null&&e.getEntity().getUUID().equals(account)){denied=true;e.setCanceled(true);}}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void protection(BlockEvent.BreakEvent e){if(enabled()&&deny&&e.getPlayer().getUUID().equals(account)){denied=true;e.setCanceled(true);}}
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_hazards",phase(),"sagadyssey_war_hazards:empty",11000,0,true,restoring()?WarHazardGameTests::resume:WarHazardGameTests::run)):List.of();}
    private static void run(GameTestHelper h){try{
        var f=new Scene();f.h=h;var s=h.getLevel().getServer();var l=h.getLevel();f.o=h.absolutePos(BlockPos.ZERO).offset(3072,-3,0);force(f);
        for(int x=0;x<48;x++)for(int z=0;z<28;z++)for(int y=-3;y<6;y++)l.setBlockAndUpdate(f.o.offset(x,y,z),(y<=0?Blocks.STONE:Blocks.AIR).defaultBlockState());
        f.p=WarMusterGameTests.battlePlayer(h,"HazardLord",BlockPos.ZERO);account=f.p.getUUID();f.p.getInventory().setItem(3,new ItemStack(Items.EMERALD,64));f.n=person(f);
        f.n.getPersonalInventory().setItem(2,new ItemStack(Items.POINTED_DRIPSTONE,phase().equals("supply")?0:8));f.n.getPersonalInventory().setItem(3,new ItemStack(Items.COBWEB,8));f.n.getPersonalInventory().setItem(4,new ItemStack(Items.OAK_PLANKS,16));f.n.getPersonalInventory().setItem(5,new ItemStack(Items.SHEARS));
        if(phase().equals("full"))for(int i=6;i<f.n.getPersonalInventory().getContainerSize();i++)f.n.getPersonalInventory().setItem(i,new ItemStack(Items.DIRT,64));
        if(tunnel())prepareTunnel(f);
        command(f,"saga war muster create Hazards");f.army=WarMusterEntry.armyId(CharacterRegistry.currentId(f.p),"Hazards");var unit=WarMusterEntry.unitId(f.army,"Crew");
        var q=h.startSequence().thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().owns(CharacterRegistry.currentId(f.p),f.army),"original army durable"))
            .thenExecute(()->command(f,"saga war muster unit Hazards Crew sword"))
            .thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().unit(unit).filter(u->u.recorded()&&WarMusterRuntime.existing(s).orElseThrow().book().durable(u)).isPresent(),"original unit durable"))
            .thenExecute(()->f.service=WarMusterGameTests.battleOffer(h,f.p,f.n,"Hazards","Crew",WarMilitiaRequest.Role.ENGINEER))
            .thenWaitUntil(()->h.assertTrue(active(f),"original engineer consent and labor active"));
        if(Set.of("scout","disarm","spike-disarm","stale","shears","disarm-protection","survey-cancel").contains(phase())){scout(q,f);return;}
        q.thenExecute(()->{deny=phase().equals("protection");approve(f);});
        if(Set.of("cancel","retire").contains(phase())){
            q.thenWaitUntil(()->h.assertTrue(job(f).next()==4&&job(f).phase()==WarEngineeringJob.Phase.ACTIVE&&runtime(f).durable(job(f)),"one supported real section before stop"))
             .thenExecute(()->command(f,phase().equals("retire")?"saga war muster retire "+f.service:"saga war engineer pause "+f.job));
            if(phase().equals("cancel"))q.thenIdle(100).thenExecute(()->{h.assertTrue(job(f).next()==4,"pause keeps future route unknown and untouched");command(f,"saga war engineer cancel "+f.job);});
            q.thenWaitUntil(()->h.assertTrue(job(f).phase()==WarEngineeringJob.Phase.CANCELLED&&runtime(f).durable(job(f)),"partial route closes without undo"))
             .thenExecute(()->h.assertTrue(f.n.getPersonalInventory().countItem(Items.OAK_PLANKS)==15&&f.n.getPersonalInventory().countItem(Items.COBBLESTONE)==3&&f.n.getPersonalInventory().getItem(1).getDamageValue()==3,"partial support and real goods remain once"));close(q,f);return;
        }
        if(saving()){
            q.thenWaitUntil(()->h.assertTrue(phase().equals("save")?job(f).next()==4&&job(f).phase()==WarEngineeringJob.Phase.ACTIVE:job(f).next()==0&&job(f).phase()==WarEngineeringJob.Phase.ISSUING,"chosen pre-stop work boundary"))
             .thenExecute(()->f.n.setNoAi(true)).thenWaitUntil(()->h.assertTrue(runtime(f).durable(job(f)),"chosen work state durable"))
             .thenExecute(()->{h.assertTrue(job(f).cells().subList(phase().equals("save")?4:1,job(f).cells().size()).stream().allMatch(c->c.before().equals(WarEngineeringJob.UNOBSERVED)),"future tunnel is never scanned");capture(f,"SAVED");}).thenSucceed();return;
        }
        if(Set.of("ore","asset","falling").contains(phase())){
            int stopped=phase().equals("falling")?2:6;
            q.thenWaitUntil(()->h.assertTrue(job(f).next()==stopped&&runtime(f).durable(job(f)),"work reaches first unsafe exposed section"))
             .thenIdle(180).thenExecute(()->{h.assertTrue(job(f).next()==stopped&&job(f).phase()==WarEngineeringJob.Phase.ACTIVE,"unsafe face stops without bypass");h.assertTrue(job(f).cells().subList(stopped+1,job(f).cells().size()).stream().allMatch(c->c.before().equals(WarEngineeringJob.UNOBSERVED)),"blocked work never reveals future");command(f,"saga war engineer cancel "+f.job);});
            close(q,f);return;
        }
        if(phase().equals("water"))q.thenWaitUntil(()->h.assertTrue(job(f).next()==2&&runtime(f).durable(job(f)),"real neighbor water halts the first roof"))
            .thenIdle(120).thenExecute(()->{
                h.assertTrue(job(f).next()==2&&job(f).cells().get(4).before().equals(WarEngineeringJob.UNOBSERVED)
                        &&l.getBlockState(f.o.offset(15,3,10)).is(Blocks.WATER),"water remains intact and later work stays unknown");
                l.setBlockAndUpdate(f.o.offset(15,3,10),Blocks.STONE.defaultBlockState());
            });
        if(phase().equals("blocked-route"))q.thenIdle(150).thenExecute(()->{
            h.assertTrue(job(f).next()==0&&job(f).unobserved()&&FieldworkData.get(s).receiptsFor(f.n.getUUID()).isEmpty(),"blocked original stand forbids all excavation");
            l.setBlockAndUpdate(f.o.offset(13,1,10),Blocks.AIR.defaultBlockState());
        });
        if(phase().equals("supply"))q.thenWaitUntil(()->h.assertTrue(job(f).next()==2,"two real excavations precede missing spike"))
            .thenIdle(120).thenExecute(()->{h.assertTrue(job(f).next()==2&&l.getBlockState(f.o.offset(14,-1,10)).isAir()&&f.n.getPersonalInventory().getItem(1).getDamageValue()==2,"no material gives no free native spike");f.h.assertTrue(f.n.getPersonalInventory().insert(new ItemStack(Items.POINTED_DRIPSTONE,8)).isEmpty(),"fixture late delivery preserves excavated cargo");});
        if(phase().equals("full"))q.thenWaitUntil(()->h.assertTrue(!job(f).unobserved(),"first visible work face bound before inventory validation"))
            .thenIdle(100).thenExecute(()->{h.assertTrue(job(f).next()==0&&f.n.getPersonalInventory().getItem(1).getDamageValue()==0&&f.n.getPersonalInventory().countItem(Items.COBBLESTONE)==0,"full inventory never destroys the observed tunnel face or loses loot");f.n.getPersonalInventory().removeItemNoUpdate(f.n.getPersonalInventory().getContainerSize()-1);});
        if(phase().equals("protection"))q.thenWaitUntil(()->h.assertTrue(denied&&job(f).phase()==WarEngineeringJob.Phase.ACTIVE,"native protection confirms rollback"))
            .thenExecute(()->{h.assertTrue(job(f).next()==0&&f.n.getPersonalInventory().countItem(Items.COBWEB)==8&&FieldworkData.get(s).receiptsFor(f.n.getUUID()).isEmpty(),"protected placement retains exact original material and no receipt");deny=false;});
        q.thenWaitUntil(()->h.assertTrue(job(f).phase()==WarEngineeringJob.Phase.DONE&&runtime(f).durable(job(f)),"finite mixed work completes "+runtime(f).status(f.p,f.army)+" body="+f.n.position()));
        q.thenExecute(()->verify(f));
        if(phase().equals("full"))q.thenExecute(()->f.n.getPersonalInventory().removeItemNoUpdate(f.n.getPersonalInventory().getContainerSize()-2));
        if(phase().equals("spike"))spikePhysics(q,f);if(phase().equals("web"))webPhysics(q,f);
        close(q,f);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static void prepareTunnel(Scene f){var l=f.h.getLevel();int max=phase().equals("bend")?3:4;
        for(int x=14;x<=max+13;x++)for(int z=8;z<=13;z++)for(int y=1;y<=4;y++)l.setBlockAndUpdate(f.o.offset(x,y,z),Blocks.STONE.defaultBlockState());
        if(phase().equals("adopt")){for(int y=1;y<=2;y++)l.setBlockAndUpdate(f.o.offset(14,y,10),Blocks.CAVE_AIR.defaultBlockState());l.setBlockAndUpdate(f.o.offset(14,3,10),Blocks.OAK_PLANKS.defaultBlockState());}
        if(phase().equals("blocked-route"))l.setBlockAndUpdate(f.o.offset(13,1,10),Blocks.STONE.defaultBlockState());
        if(phase().equals("ore"))l.setBlockAndUpdate(f.o.offset(15,3,10),Blocks.DIAMOND_ORE.defaultBlockState());
        if(phase().equals("asset")){l.setBlockAndUpdate(f.o.offset(15,3,10),Blocks.CHEST.defaultBlockState());((ChestBlockEntity)l.getBlockEntity(f.o.offset(15,3,10))).setItem(0,new ItemStack(Items.DIAMOND,3));}
        if(phase().equals("water"))l.setBlockAndUpdate(f.o.offset(15,3,10),Blocks.WATER.defaultBlockState());
        if(phase().equals("falling"))l.setBlockAndUpdate(f.o.offset(14,4,10),Blocks.SAND.defaultBlockState());
    }
    private static void approve(Scene f){
        String text;if(tunnel())text="tunnel "+f.service+" "+xyz(f.o.offset(13,1,10))+" "+xyz(f.o.offset(16,1,10))+" "+xyz(f.o.offset(16,1,phase().equals("bend")?12:10));
        else text=(Set.of("web","protection").contains(phase())?"web_snare":"spike_pit")+" left "+f.service+" "+xyz(f.o.offset(14,Set.of("web","protection").contains(phase())?1:0,10))+" "+xyz(f.o.offset(16,Set.of("web","protection").contains(phase())?1:0,10));
        command(f,"saga war engineer "+text);confirm(f);
        f.h.assertTrue(job(f).cells().stream().allMatch(c->c.before().equals(WarEngineeringJob.UNOBSERVED)),"preview and approval contain only unknown before states");
    }
    private static void confirm(Scene f){command(f,WarMusterGameTests.latestConfirmation(f.p).substring(1));f.job=runtime(f).entries().stream().filter(j->j.service().equals(f.service)).max(Comparator.comparingLong(WarEngineeringJob::createdAt).thenComparing(WarEngineeringJob::id)).orElseThrow().id();f.jobs.add(f.job);}
    private static void verify(Scene f){var l=f.h.getLevel();var j=job(f);var data=FieldworkData.get(l.getServer());
        f.h.assertTrue(data.receiptsFor(f.n.getUUID()).stream().filter(r->r.action().planId().equals(j.id())).count()==j.next()-j.adopted().size(),"every paid step has exactly one original fieldwork receipt");
        if(tunnel()||phase().equals("resume")){
            int sections=phase().equals("bend")?5:3;for(int i=0;i<j.cells().size();i+=4){var p=j.cells().get(i+1).position();var at=new BlockPos(p.x(),p.y(),p.z());f.h.assertTrue(l.getBlockState(at).isAir()&&l.getBlockState(at.above()).isAir()&&l.getBlockState(at.above(2)).is(Blocks.OAK_PLANKS),"actual two-clear supported tunnel");}
            int adopted=phase().equals("adopt")?1:0;f.h.assertTrue(f.n.getPersonalInventory().countItem(Items.OAK_PLANKS)==16-sections+adopted&&f.n.getPersonalInventory().countItem(Items.COBBLESTONE)==3*(sections-adopted)&&f.n.getPersonalInventory().getItem(1).getDamageValue()==3*(sections-adopted),"native support material, loot and tool damage agree");
        }else if(Set.of("spike","supply").contains(phase())){
            for(int x=14;x<=16;x++)f.h.assertTrue(l.getBlockState(f.o.offset(x,0,10)).isAir()&&l.getBlockState(f.o.offset(x,-1,10)).equals(Blocks.POINTED_DRIPSTONE.defaultBlockState()),"real open two-block pit and upward spike");
            f.h.assertTrue(f.n.getPersonalInventory().countItem(Items.POINTED_DRIPSTONE)==5&&f.n.getPersonalInventory().countItem(Items.COBBLESTONE)==6&&f.n.getPersonalInventory().getItem(1).getDamageValue()==6,"three actual spikes, six actual excavation outputs and wear");
            f.h.assertTrue(WarHazardRuntime.existing(l.getServer()).orElseThrow().memory(f.army).orElseThrow().observations().size()==3,"own hazards reported from actual receipts");
        }else if(Set.of("web","protection").contains(phase()))f.h.assertTrue(f.n.getPersonalInventory().countItem(Items.COBWEB)==5,"each native web consumes one original item");
    }
    private static LivingEntity victim(Scene f,BlockPos at){var pig=f.h.spawn(EntityType.PIG,BlockPos.ZERO);pig.setPersistenceRequired();pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);pig.setHealth(1000);pig.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);f.victims.add(pig);return pig;}
    private static void spikePhysics(GameTestSequence q,Scene f){q.thenExecute(()->victim(f,f.o.offset(14,4,10)))
        .thenWaitUntil(()->f.h.assertTrue(f.victims.getFirst().getHealth()<1000&&f.victims.getFirst().onGround(),"native gravity and stalagmite damage occurred"))
        .thenExecute(()->{var v=f.victims.getFirst();f.h.assertTrue(v.getLastDamageSource()!=null&&v.getLastDamageSource().is(net.minecraft.world.damagesource.DamageTypes.STALAGMITE),"native stalagmite damage source, no custom damage injection");f.physics.addProperty("stalagmiteHealth",v.getHealth());});}
    private static void webPhysics(GameTestSequence q,Scene f){q.thenExecute(()->{victim(f,f.o.offset(14,4,10));victim(f,f.o.offset(24,4,10));}).thenIdle(20)
        .thenExecute(()->{var web=f.victims.getFirst();var control=f.victims.get(1);f.h.assertTrue(web.getY()>control.getY()+.25&&control.onGround(),"native web entanglement slows falling against actual clear-ground control");f.physics.addProperty("webY",web.getY());f.physics.addProperty("controlY",control.getY());});}
    private static void scout(GameTestSequence q,Scene f){var l=f.h.getLevel();var visible=f.o.offset(14,1,10);var hidden=f.o.offset(14,1,6);var stand=f.o.offset(12,1,10);
        if(phase().equals("survey-cancel")){
            q.thenExecute(()->{command(f,"saga war engineer survey "+f.service+" "+xyz(stand)+" "+xyz(visible)+" "+xyz(visible));var r=WarHazardRuntime.existing(l.getServer()).orElseThrow();command(f,"saga war engineer cancelsurvey "+f.service);f.h.assertTrue(!r.surveying(f.service),"cancel removes read-only duty");approveWeb(f);})
             .thenWaitUntil(()->f.h.assertTrue(job(f).phase()==WarEngineeringJob.Phase.DONE&&runtime(f).durable(job(f)),"same original worker can accept construction after cancelled survey"));close(q,f);return;
        }
        q.thenExecute(()->{l.setBlockAndUpdate(visible,(phase().equals("spike-disarm")?Blocks.POINTED_DRIPSTONE:Blocks.COBWEB).defaultBlockState());l.setBlockAndUpdate(hidden,Blocks.POINTED_DRIPSTONE.defaultBlockState());for(int x=10;x<=16;x++)for(int y=1;y<=3;y++)l.setBlockAndUpdate(f.o.offset(x,y,8),Blocks.STONE.defaultBlockState());
            command(f,"saga war engineer survey "+f.service+" "+xyz(stand)+" "+xyz(hidden)+" "+xyz(visible));})
         .thenWaitUntil(()->f.h.assertTrue(WarHazardRuntime.existing(l.getServer()).flatMap(r->r.memory(f.army)).filter(m->m.observations().size()==1&&WarHazardRuntime.existing(l.getServer()).orElseThrow().durable(m)).isPresent(),"actual visible trap reported and occluded trap stays unknown"))
         .thenExecute(()->{var r=WarHazardRuntime.existing(l.getServer()).orElseThrow();var seen=r.memory(f.army).orElseThrow().observations().getFirst();f.h.assertTrue(seen.position().equals(pos(f,visible))&&seen.observer().equals(f.n.getCharacterId()),"same original observer sees only visible web");
            f.h.assertTrue(r.blocked(f.army,pos(f,visible))&&!r.blocked(UUID.randomUUID(),pos(f,visible)),"army isolation: foreign intelligence is unavailable");
            f.h.assertTrue(!WarNativeBindings.currentBuild().actors().canStand(f.n,pos(f,visible)),"own known hazard blocks voluntary cautious movement");
            command(f,"saga war engineer caution Hazards false");f.h.assertTrue(WarNativeBindings.currentBuild().hazards().mayStand(f.n,pos(f,visible)),"lord may choose risk without deleting physical trap");command(f,"saga war engineer caution Hazards true");
            var stranger=WarMusterGameTests.battlePlayer(f.h,"HazardOther",BlockPos.ZERO);f.h.assertTrue(WarMusterGameTests.command(stranger,"saga war engineer disarm "+f.service+" "+seen.id())==0,"operator has no foreign engineer authority");
            boolean privateReport=false;try{r.status(stranger,f.army);}catch(IllegalArgumentException rejected){privateReport=true;}f.h.assertTrue(privateReport,"a foreign operator cannot read another army's reports");
            if(phase().equals("stale")){l.setBlockAndUpdate(visible,Blocks.STONE.defaultBlockState());command(f,"saga war engineer disarm "+f.service+" "+seen.id());confirm(f);}
            else if(!phase().equals("scout")){deny=phase().equals("disarm-protection");if(phase().equals("shears"))f.n.getPersonalInventory().removeItemNoUpdate(5);command(f,"saga war engineer disarm "+f.service+" "+seen.id());confirm(f);}
        });
        if(phase().equals("scout")){q.thenExecute(()->{l.setBlockAndUpdate(visible,Blocks.AIR.defaultBlockState());command(f,"saga war engineer survey "+f.service+" "+xyz(stand)+" "+xyz(visible)+" "+xyz(visible));})
            .thenWaitUntil(()->f.h.assertTrue(WarHazardRuntime.existing(l.getServer()).orElseThrow().memory(f.army).orElseThrow().observations().isEmpty(),"fresh visible clear removes stale mark; hidden target still unknown"));}
        else if(phase().equals("stale"))q.thenIdle(180).thenExecute(()->{f.h.assertTrue(job(f).next()==0&&l.getBlockState(visible).is(Blocks.STONE)&&FieldworkData.get(l.getServer()).receiptsFor(f.n.getUUID()).isEmpty(),"changed disarm target never removes foreign stone or consumes tool");command(f,"saga war engineer cancel "+f.job);});
        else{
            if(phase().equals("disarm-protection"))q.thenWaitUntil(()->f.h.assertTrue(denied&&job(f).phase()==WarEngineeringJob.Phase.ACTIVE,"native disarm protection rolled back"))
                .thenExecute(()->{f.h.assertTrue(l.getBlockState(visible).is(Blocks.COBWEB)&&f.n.getPersonalInventory().getItem(5).getDamageValue()==0&&f.n.getPersonalInventory().countItem(Items.COBWEB)==8&&FieldworkData.get(l.getServer()).receiptsFor(f.n.getUUID()).isEmpty(),"cancelled disarm retains real web, item count, tool and no receipt");deny=false;});
            if(phase().equals("shears"))q.thenIdle(180).thenExecute(()->{f.h.assertTrue(job(f).next()==0&&l.getBlockState(visible).is(Blocks.COBWEB),"web cannot be disarmed without actual shears");f.n.getPersonalInventory().setItem(5,new ItemStack(Items.SHEARS));});
            q.thenWaitUntil(()->f.h.assertTrue(job(f).phase()==WarEngineeringJob.Phase.DONE&&runtime(f).durable(job(f)),"actual shears disarm completes"))
             .thenExecute(()->{boolean spike=phase().equals("spike-disarm");f.h.assertTrue(l.getBlockState(visible).isAir()&&(spike?f.n.getPersonalInventory().countItem(Items.POINTED_DRIPSTONE)==9&&f.n.getPersonalInventory().getItem(1).getDamageValue()==1:f.n.getPersonalInventory().countItem(Items.COBWEB)==9&&f.n.getPersonalInventory().getItem(5).getDamageValue()==1),"native trap loot and actual selected tool wear with original transaction");f.h.assertTrue(WarHazardRuntime.existing(l.getServer()).orElseThrow().memory(f.army).orElseThrow().observations().isEmpty(),"actual disarm clears own observation");});
        }close(q,f);
    }
    private static void approveWeb(Scene f){command(f,"saga war engineer web_snare left "+f.service+" "+xyz(f.o.offset(14,1,10))+" "+xyz(f.o.offset(16,1,10)));confirm(f);}
    private static void close(GameTestSequence q,Scene f){q.thenExecute(()->command(f,"saga war muster retire "+f.service))
        .thenWaitUntil(()->f.h.assertTrue(WarMusterRuntime.existing(f.h.getLevel().getServer()).orElseThrow().book().offer(f.service).filter(o->o.stage()==WarMusterEntry.Stage.ENDED&&WarMusterRuntime.existing(f.h.getLevel().getServer()).orElseThrow().book().durable(o)).isPresent()&&f.p.getInventory().countItem(Items.EMERALD)==63&&f.n.getPersonalInventory().countItem(Items.EMERALD)==1,"original duty closes with actual once-only base wage"))
        .thenExecute(()->capture(f,"COMPLETE")).thenSucceed();}
    private static void resume(GameTestHelper h){try{
        var s=h.getLevel().getServer();var saved=read(s.getWorldPath(LevelResource.ROOT).resolve("war-hazards-save.json"));h.assertTrue(saved.get("process").getAsLong()!=ProcessHandle.current().pid(),"independent original-world JVM");var f=new Scene();f.h=h;f.o=BlockPos.of(saved.get("origin").getAsLong());force(f);
        f.p=WarMusterGameTests.restoreBattlePlayer(h,new GameProfile(UUID.fromString(saved.get("account").getAsString()),saved.get("name").getAsString()),f.o.offset(5,1,8));account=f.p.getUUID();f.army=UUID.fromString(saved.get("army").getAsString());f.service=UUID.fromString(saved.get("service").getAsString());f.job=UUID.fromString(saved.get("job").getAsString());f.jobs.add(f.job);var body=UUID.fromString(saved.get("body").getAsString());
        var q=h.startSequence().thenWaitUntil(()->{var e=h.getLevel().getEntity(body);h.assertTrue(e instanceof NpcBase,"same original engineer body loads");f.n=(NpcBase)e;h.assertTrue(active(f),"same original engineer service");}).thenExecute(()->f.n.setNoAi(false));
        if(phase().equals("issue-resume")){q.thenWaitUntil(()->h.assertTrue(job(f).phase()==WarEngineeringJob.Phase.REVIEW&&runtime(f).durable(job(f)),"cold mixed intent without original receipt stays review"))
            .thenExecute(()->command(f,"saga war muster retire "+f.service)).thenIdle(100).thenExecute(()->{h.assertTrue(job(f).next()==0&&WorkCommitmentRuntime.blocksCivil(f.n)&&FieldworkData.get(s).receiptsFor(f.n.getUUID()).isEmpty(),"unresolved intent never replays or releases body");capture(f,"REVIEW");}).thenSucceed();return;}
        q.thenWaitUntil(()->h.assertTrue(job(f).phase()==WarEngineeringJob.Phase.DONE&&runtime(f).durable(job(f)),"original mixed route resumes once"))
            .thenExecute(()->verify(f));close(q,f);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static NpcBase person(Scene f){var n=f.h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);n.teleportTo(f.o.getX()+5.5,f.o.getY()+1,f.o.getZ()+5.5);n.setPersistenceRequired();n.setProfession(NpcProfession.WORKER);n.setCommand(NpcCommand.STAY);n.setIdentity(NpcSocialClass.COMMONER,NpcOffice.NONE);for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));n.getPersonalInventory().clearContent();n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,16));n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));var life=NpcLifeData.get(f.h.getLevel().getServer());life.onRecruited(n,CharacterRegistry.currentId(f.p),f.h.getLevel().getGameTime());life.setHunger(n.getCharacterId(),100,f.h.getLevel().getGameTime());return n;}
    private static boolean active(Scene f){var r=WarMusterRuntime.existing(f.h.getLevel().getServer()).orElseThrow();return r.book().offer(f.service).filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE&&r.book().durable(o)).isPresent();}
    private static WarEngineeringRuntime runtime(Scene f){return WarEngineeringRuntime.existing(f.h.getLevel().getServer()).orElseThrow();}
    private static WarEngineeringJob job(Scene f){return runtime(f).find(f.job).orElseThrow();}
    private static WarIntent.Position pos(Scene f,BlockPos p){return new WarIntent.Position(WarKey.parse(f.h.getLevel().dimension().identifier().toString()),p.getX(),p.getY(),p.getZ());}
    private static void command(Scene f,String text){f.h.assertTrue(WarMusterGameTests.command(f.p,text)==1,"ordinary command "+text+"; "+WarMusterGameTests.feedback(f.p));}
    private static String xyz(BlockPos p){return p.getX()+" "+p.getY()+" "+p.getZ();}
    private static void force(Scene f){for(int x=f.o.getX()>>4;x<=(f.o.getX()+48)>>4;x++)for(int z=f.o.getZ()>>4;z<=(f.o.getZ()+28)>>4;z++)f.h.getLevel().setChunkForced(x,z,true);}
    private static void capture(Scene f,String stage){try{
        var data=new JsonObject();data.addProperty("process",ProcessHandle.current().pid());data.addProperty("phase",phase());data.addProperty("stage",stage);data.addProperty("origin",f.o.asLong());data.addProperty("account",f.p.getUUID().toString());data.addProperty("name",f.p.getGameProfile().name());data.addProperty("army",f.army.toString());data.addProperty("service",f.service.toString());data.addProperty("job",f.job==null?"":f.job.toString());data.add("jobs",new Gson().toJsonTree(f.jobs));data.addProperty("body",f.n.getUUID().toString());data.addProperty("person",f.n.getCharacterId().toString());data.addProperty("playerCash",f.p.getInventory().countItem(Items.EMERALD));data.addProperty("npcCash",f.n.getPersonalInventory().countItem(Items.EMERALD));
        data.add("physics",f.physics);var counts=new JsonObject();for(var item:List.of(Items.POINTED_DRIPSTONE,Items.COBWEB,Items.OAK_PLANKS,Items.COBBLESTONE))counts.addProperty(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(),f.n.getPersonalInventory().countItem(item));data.add("materials",counts);data.addProperty("pickWear",f.n.getPersonalInventory().getItem(1).getDamageValue());data.addProperty("shearsWear",f.n.getPersonalInventory().getItem(5).getDamageValue());var root=f.h.getLevel().getServer().getWorldPath(LevelResource.ROOT);write(root.resolve("war-hazards-"+phase()+".json"),data);if(saving())write(root.resolve("war-hazards-save.json"),data);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    private static void write(Path p,JsonObject v)throws Exception{Files.writeString(p,new GsonBuilder().setPrettyPrinting().create().toJson(v));}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopped(ServerStoppedEvent e)throws Exception{if(enabled()&&saving()){var root=e.getServer().getWorldPath(LevelResource.ROOT);if(!Files.exists(root.resolve("war-hazards-save.json")))return;var p=read(root.resolve("war-hazards-save.json"));var data=NbtIo.readCompressed(root.resolve("level.dat"),NbtAccounter.create(64L*1024*1024)).getCompoundOrEmpty("Data");p.addProperty("gameTime",data.getLongOr("Time",0));p.addProperty("dayTime",data.getLongOr("DayTime",0));write(root.resolve("war-hazards-save.json"),p);}}
}
