package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.faction.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Stage;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarProjectileSafety;
import com.jgeted.sagadyssey.social.work.WorkCommitmentRuntime;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.nio.file.Files;
import java.util.*;

/** Fixture creates the scene only. Production muster, map, original opposition, wounds, arrows and settlement do the work. */
@GameTestHolder("sagadyssey_war_battle") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarBattleGameTests {
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.battle.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.battle.phase","battle");}
    @SubscribeEvent public static void started(ServerStartedEvent e){if(enabled()&&e.getServer() instanceof GameTestServer)try{
        e.getServer().overworld().getStructureManager().getOrCreate(net.minecraft.resources.Identifier.parse("sagadyssey_war_battle:empty"))
                .load(e.getServer().overworld().holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[40,4,32],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }catch(Exception x){throw new IllegalStateException(x);}}
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_battle",phase(),"sagadyssey_war_battle:empty",6000,0,true,phase().equals("resume")?WarBattleGameTests::resume:WarBattleGameTests::battle)):List.of();}
    private static void battle(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();for(int x=0;x<40;x++)for(int z=0;z<28;z++){h.setBlock(new BlockPos(x,0,z),Blocks.STONE);for(int y=1;y<8;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);}
        var player=WarMusterGameTests.battlePlayer(h,"BattleLord",new BlockPos(6,1,8));var actor=CharacterRegistry.currentId(player);
        var life=NpcLifeData.get(server);var soldiers=new ArrayList<NpcBase>();var ids=new ArrayList<UUID>();
        for(int i=0;i<4;i++){var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(4+i*3,1,5));place(h,n,new BlockPos(4+i*3,1,5));n.setProfession(NpcProfession.WORKER);n.setCommand(NpcCommand.WORK);
            for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();
            n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(i==2?Items.BOW:Items.IRON_SWORD));n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,8));if(i==2){n.getPersonalInventory().setItem(1,new ItemStack(Items.ARROW,32));n.getPersonalInventory().setItem(2,new ItemStack(Items.IRON_SWORD));}
            life.onRecruited(n,actor,level.getGameTime());life.setHunger(n.getCharacterId(),300,level.getGameTime());soldiers.add(n);
        }
        var foe=target(h,new BlockPos(29,1,20));var bystander=target(h,new BlockPos(15,1,14));
        bystander.setFaction(FactionRegistry.get("sagadyssey:church"));
        foe.getAttribute(Attributes.MAX_HEALTH).setBaseValue(120);foe.setHealth(120); // explicit starting fixture durability; never healed after contact
        player.getInventory().setItem(3,new ItemStack(Items.EMERALD,16));
        h.assertTrue(WarMusterGameTests.command(player,"saga war muster create FieldArmy")==1,"production create");var muster=WarMusterRuntime.existing(server).orElseThrow();
        var army=WarMusterEntry.armyId(actor,"FieldArmy");var guard=WarMusterEntry.unitId(army,"Guard");var bow=WarMusterEntry.unitId(army,"Bow");var reserve=WarMusterEntry.unitId(army,"Reserve");UUID[] episode={null};WarShotPermit[] revokedShot={null};long[] defenseAt={0};int[] before={0};
        h.onEachTick(()->{long age=level.getGameTime()-defenseAt[0];if(defenseAt[0]>0&&age>=0&&age<240&&age%20==0)org.slf4j.LoggerFactory.getLogger(WarBattleGameTests.class).info("BATTLE_TRACE age={} reports={} targets={}",age,field(h).reports(actor,army),soldiers.stream().map(n->n.getTarget()==null?"none":n.getTarget().getUUID().toString()).toList());});
        h.startSequence().thenWaitUntil(()->h.assertTrue(muster.book().owns(actor,army),"army durable"))
            .thenExecute(()->{WarMusterGameTests.command(player,"saga war muster unit FieldArmy Guard sword");WarMusterGameTests.command(player,"saga war muster unit FieldArmy Bow bow");WarMusterGameTests.command(player,"saga war muster unit FieldArmy Reserve sword");})
            .thenWaitUntil(()->h.assertTrue(List.of(guard,bow,reserve).stream().allMatch(id->muster.book().unit(id).filter(u->u.recorded()&&muster.book().durable(u)).isPresent()),"three production units ready"))
            .thenExecute(()->{for(int i=0;i<4;i++)ids.add(WarMusterGameTests.battleOffer(h,player,soldiers.get(i),"FieldArmy",i==2?"Bow":i==3?"Reserve":"Guard"));player.setPos(h.absolutePos(new BlockPos(8,1,7)).getCenter());})
            .thenWaitUntil(()->h.assertTrue(ids.size()==4&&ids.stream().allMatch(id->muster.book().offer(id).filter(o->o.stage()==Stage.ACTIVE&&muster.book().durable(o)).isPresent()),"real original military labor: "+muster.status(actor)))
            .thenExecute(()->{
                player.setPos(h.absolutePos(new BlockPos(10,1,8)).getCenter());
                for(var n:soldiers){n.setFaction(FactionRegistry.get("sagadyssey:kingdom"));life.setHunger(n.getCharacterId(),650,level.getGameTime());}
                soldiers.get(3).setFaction(FactionRegistry.get("sagadyssey:wilderness"));place(h,soldiers.get(3),new BlockPos(16,1,14));
                PoliticalStateData.get(server).setRelation(server,"sagadyssey:kingdom","sagadyssey:wilderness",PoliticalRelation.NEUTRAL,level.getGameTime());
                PoliticalStateData.get(server).setRelation(server,"sagadyssey:kingdom","sagadyssey:church",PoliticalRelation.NEUTRAL,level.getGameTime());
                issue(h,player,guard,new BlockPos(14,1,18));
            }).thenWaitUntil(()->h.assertTrue(field(h).reports(actor,army).stream().filter(r->r.unit().equals(guard)).count()==2&&field(h).reports(actor,army).stream().filter(r->r.unit().equals(guard)).allMatch(r->r.state()==WarFieldReport.State.HOLDING),"guard order delivered"))
            .thenExecute(()->issue(h,player,bow,new BlockPos(14,1,8)))
            .thenWaitUntil(()->h.assertTrue(field(h).reports(actor,army).size()==4&&field(h).reports(actor,army).stream().allMatch(r->r.unit().equals(reserve)?r.state()==WarFieldReport.State.WAITING&&r.reason().equals("NO_DURABLE_RECEIVED_ORDER"):r.state()==WarFieldReport.State.HOLDING),"actual map orders reach physical stations; reserve receives no order: "+field(h).reports(actor,army)))
            .thenExecute(()->{place(h,foe,new BlockPos(14,1,18));before[0]=InjuryData.get(server).wounds().size();})
            .thenIdle(60).thenExecute(()->{
                h.assertTrue(InjuryData.get(server).wounds().size()==before[0]&&soldiers.stream().allMatch(n->n.getTarget()==null),"map orders and military consent do not make neutral NPCs enemies");
                h.assertTrue(soldiers.get(0).hurtServer(level,level.damageSources().mobAttack(foe),1),"real original assault, not an injected encounter");defenseAt[0]=level.getGameTime();
            }).thenWaitUntil(()->{
                var encounters=CombatEncounterData.get(server);var e=encounters.activeFor(soldiers.get(0).getCharacterId(),level.getGameTime());h.assertTrue(e!=null&&e.sideOf(soldiers.get(1).getCharacterId())!=null&&e.sideOf(soldiers.get(2).getCharacterId())!=null,"nearby same-army original people join actual defense across two units");
                h.assertTrue(e.sideOf(soldiers.get(1).getCharacterId()).rule()==EngagementRule.SELF_DEFENSE_ONLY,"group join preserves original self-defense rule");episode[0]=e.id();
                h.assertTrue(InjuryData.get(server).wounds().stream().anyMatch(w->w.patientCharacterId().equals(foe.getCharacterId())&&soldiers.get(1).getCharacterId().equals(w.attackerCharacterId())),"unattacked second guard really defends its consenting comrade");
            }).thenWaitUntil(()->h.assertTrue(CombatEncounterData.get(server).encounter(episode[0]).events().stream().anyMatch(e->e.type()==EncounterEventType.SHOT&&e.subjectCharacterId().equals(soldiers.get(2).getCharacterId())),"cross-unit archer consumes a real arrow in the original episode"))
            .thenWaitUntil(()->h.assertTrue(level.getGameTime()-defenseAt[0]>110&&soldiers.stream().allMatch(n->n.getTarget()==null),"expired actual assault cannot be laundered into permanent military hostility"))
            .thenWaitUntil(()->h.assertTrue(CombatEncounterData.get(server).encounter(episode[0]).status()==EncounterStatus.ENDED,"original maintenance freezes and settles the real episode"))
            .thenExecute(()->{
                h.assertTrue(CombatEncounterData.get(server).settling(episode[0]),"original writeback snapshot exists");h.assertTrue(InjuryData.get(server).woundsOf(bystander.getCharacterId(),level.getGameTime()).isEmpty(),"neutral bystander never targeted");
                // Open the new engagement within bow range and beyond the sword unit's hold radius.
                h.assertTrue(foe.isAlive(),"the original attacker survives the expired defense episode");
                place(h,foe,new BlockPos(14,1,1));
                PoliticalStateData.get(server).setRelation(server,"sagadyssey:kingdom","sagadyssey:wilderness",PoliticalRelation.WAR,level.getGameTime());
            }).thenWaitUntil(()->{
                var arrows=level.getEntitiesOfClass(AbstractArrow.class,soldiers.get(2).getBoundingBox().inflate(24),a->a.getOwner()==soldiers.get(2)&&WarProjectileSafety.read(a).isPresent());
                h.assertTrue(!arrows.isEmpty(),"original declared war starts a new production encounter and a real shot");var arrow=arrows.getFirst();var permit=WarProjectileSafety.read(arrow).orElseThrow();
                h.assertTrue(!permit.encounter().equals(episode[0])&&field(h).mayImpact(permit,foe),"new episode is independently authorized by current original politics");
                PoliticalStateData.get(server).setRelation(server,"sagadyssey:kingdom","sagadyssey:wilderness",PoliticalRelation.FRIENDLY,level.getGameTime());
                h.assertTrue(!field(h).mayImpact(permit,foe),"peace cancels a real in-flight shot immediately");var impact=new ProjectileImpactEvent(arrow,new EntityHitResult(foe));WarProjectileSafety.impact(impact);h.assertTrue(impact.isCanceled()&&arrow.isRemoved(),"native projectile event discards the denied arrow");revokedShot[0]=permit;
            }).thenIdle(30).thenExecute(()->{
                h.assertTrue(soldiers.stream().allMatch(n->n.getTarget()==null),"queued perception and attacks stop after original peace");
                PoliticalStateData.get(server).setRelation(server,"sagadyssey:kingdom","sagadyssey:wilderness",PoliticalRelation.WAR,level.getGameTime());
            }).thenWaitUntil(()->{
                var arrows=level.getEntitiesOfClass(AbstractArrow.class,soldiers.get(2).getBoundingBox().inflate(24),a->a.getOwner()==soldiers.get(2)&&WarProjectileSafety.read(a).filter(p->field(h).mayImpact(p,foe)).isPresent());
                h.assertTrue(!arrows.isEmpty(),"reopened original war permits another actual arrow before independent service withdrawal");
                var arrow=arrows.getFirst();var permit=WarProjectileSafety.read(arrow).orElseThrow();var o=muster.book().offer(ids.get(2)).orElseThrow();
                ServiceRelationshipData.get(server).endDelegation(o.id(),actor,o.request().characterId(),"fixture NPC withdraws exact service",level.getGameTime());
                h.assertTrue(!field(h).mayImpact(permit,foe),"exact service withdrawal independently cancels a previously allowed shot during continuing war");
                var impact=new ProjectileImpactEvent(arrow,new EntityHitResult(foe));WarProjectileSafety.impact(impact);h.assertTrue(impact.isCanceled()&&arrow.isRemoved(),"withdrawn native arrow is discarded");
                revokedShot[0]=permit;
                PoliticalStateData.get(server).setRelation(server,"sagadyssey:kingdom","sagadyssey:wilderness",PoliticalRelation.FRIENDLY,level.getGameTime());
                for(var id:ids)WarMusterGameTests.command(player,"saga war muster retire "+id);
            }).thenWaitUntil(()->{
                boolean released=ids.stream().allMatch(id->muster.book().offer(id).filter(o->o.stage()==Stage.ENDED&&muster.book().durable(o)).isPresent());
                if(!released){
                    var military=WarServerEvents.existing(server).orElseThrow().mobilization().orElseThrow();
                    h.assertTrue(false,"original service releases after actual combat: "+muster.status(actor)+" · "+ids.stream().map(military::progress).toList());
                }
            })
            .thenWaitUntil(()->h.assertTrue(player.getInventory().countItem(Items.EMERALD)==12&&soldiers.stream().allMatch(n->n.getPersonalInventory().count(s->s.is(Items.EMERALD))==1),"four actual wages from the unchanged real source"))
            .thenWaitUntil(()->h.assertTrue(CombatEncounterData.get(server).encounters().stream().allMatch(e->e.status()==EncounterStatus.ENDED),"original episodes settle without reset or forced victory"))
            .thenExecute(()->{
                h.assertTrue(soldiers.stream().noneMatch(WorkCommitmentRuntime::blocksCivil),"original civil labor available");h.assertTrue(soldiers.stream().allMatch(n->n.getPersonalInventory().count(s->s.is(Items.BREAD))<8),"real rations eaten during service");
                h.assertTrue(soldiers.get(2).getPersonalInventory().count(s->s.is(Items.ARROW))<32,"real ammunition spent");
                var data=CombatEncounterData.get(server);
                h.assertTrue(InjuryData.get(server).woundsOf(soldiers.get(3).getCharacterId(),level.getGameTime()).isEmpty()&&data.encounters().stream().allMatch(e->e.sideOf(soldiers.get(3).getCharacterId())==null),"unordered same-army reserve is never an enemy, even with a canonically hostile faction; it never joins without a received order");
                h.assertTrue(InjuryData.get(server).woundsOf(bystander.getCharacterId(),level.getGameTime()).isEmpty(),"bystander stays neutral through both later declarations");
                var proof=new LinkedHashMap<String,Object>();proof.put("process",ProcessHandle.current().pid());proof.put("originalPeople",3);proof.put("units",2);proof.put("ordinaryMusterAndMap",true);proof.put("neutralDenied",true);proof.put("actualGroupDefense",true);proof.put("originalPoliticalWarAndPeace",true);proof.put("realArrowPeaceFence",true);proof.put("actualFoodWagesAndRelease",true);proof.put("headlessNoOpWire",true);proof.put("normalClientAccepted",false);proof.put("encounterSchema",CombatEncounterData.get(server).save(new net.minecraft.nbt.CompoundTag(),null).getIntOr("Schema",0));
                proof.put("independentServiceArrowFence",true);proof.put("encounters",data.encounters().stream().map(e->Map.of("id",e.id(),"events",e.events().stream().map(EncounterEvent::id).toList(),"bindings",data.bindings(e.id()))).toList());proof.put("wounds",InjuryData.get(server).wounds().stream().map(WoundRecord::id).toList());proof.put("contracts",ids);
                proof.put("originalPeople",4);proof.put("units",3);proof.put("unorderedReserveRemainsFriendly",true);
                try{Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("war-battle-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(proof));}catch(Exception x){throw new IllegalStateException(x);}
            }).thenSucceed();
    }
    /** New JVM reads the actual stopped SavedData and .sgw files, not an in-memory codec rebind. */
    private static void resume(GameTestHelper h){try{
        var server=h.getLevel().getServer();var root=server.getWorldPath(LevelResource.ROOT);var proof=JsonParser.parseString(Files.readString(root.resolve("war-battle-proof.json"))).getAsJsonObject();
        h.assertTrue(proof.get("process").getAsLong()!=ProcessHandle.current().pid(),"different process");var data=CombatEncounterData.get(server);
        for(var row:proof.getAsJsonArray("encounters")){var expected=row.getAsJsonObject();var id=UUID.fromString(expected.get("id").getAsString());var actual=data.encounter(id);
            h.assertTrue(actual!=null&&actual.status()==EncounterStatus.ENDED&&data.settling(id),"original frozen settlement and ended episode restored");
            h.assertTrue(actual.events().stream().map(EncounterEvent::id).toList().equals(expected.getAsJsonArray("events").asList().stream().map(v->UUID.fromString(v.getAsString())).toList()),"event identity and sequence unchanged");
            var bindings=Arrays.asList(new Gson().fromJson(expected.get("bindings"),CombatEncounterBinding[].class));h.assertTrue(bindings.equals(data.bindings(id)),"same original host/army/principal/side references survive Schema2 disk load");
        }
        h.assertTrue(new HashSet<>(InjuryData.get(server).wounds().stream().map(WoundRecord::id).toList()).containsAll(proof.getAsJsonArray("wounds").asList().stream().map(v->UUID.fromString(v.getAsString())).toList()),"original wound records survive");
        h.startSequence().thenWaitUntil(()->{
            var muster=WarMusterRuntime.existing(server).orElseThrow();for(var id:proof.getAsJsonArray("contracts").asList().stream().map(v->UUID.fromString(v.getAsString())).toList())h.assertTrue(muster.book().offer(id).filter(o->o.stage()==Stage.ENDED&&muster.book().durable(o)).isPresent(),"ended service remains ended, no renewed grant");
        }).thenExecute(()->{try{Files.writeString(root.resolve("war-battle-resume-proof.json"),new Gson().toJson(Map.of("process",ProcessHandle.current().pid(),"savedProcess",proof.get("process").getAsLong(),"originalEncounterBindings",true,"originalSettlementAndWounds",true,"noMilitaryRegrant",true)));}catch(Exception x){throw new IllegalStateException(x);}}).thenSucceed();
    }catch(Exception x){throw new IllegalStateException("actual battle archive restart",x);}}
    private static WarFieldRuntime field(GameTestHelper h){return WarServerEvents.existing(h.getLevel().getServer()).orElseThrow().field().orElseThrow();}
    private static void issue(GameTestHelper h,ServerPlayer player,UUID unit,BlockPos relative){
        var level=h.getLevel();var at=h.absolutePos(relative);
        var map=MapItem.create(level,at.getX(),at.getZ(),(byte)0,true,true);
        Arrays.fill(MapItem.getSavedData(map,level).colors,(byte)40);
        player.setItemSlot(EquipmentSlot.MAINHAND,map);
        var maps=WarMapRuntime.get(level.getServer());var view=maps.begin(player).view().orElseThrow();
        var preview=maps.act(player,new Request(view.session(),view.revision(),Action.PREVIEW,0,unit,WarMapView.Mode.HOLD,(int)Math.round(view.sheet().pixelX(at.getX())),(int)Math.round(view.sheet().pixelZ(at.getZ())),new UUID(0,0))).view().orElseThrow();h.assertTrue(preview.preview().isPresent(),"map battle preview");
        var receipt=maps.act(player,new Request(preview.session(),preview.revision(),Action.ISSUE,0,unit,WarMapView.Mode.HOLD,0,0,preview.preview().orElseThrow().ticket())).view().orElseThrow().receipt().orElseThrow();h.assertTrue(receipt.outcome()==WarMapView.Outcome.ACCEPTED,"production map battle order: "+receipt);
    }
    private static NpcBase target(GameTestHelper h,BlockPos at){var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),at);place(h,n,at);n.setProfession(NpcProfession.HEAVY);n.setFaction(FactionRegistry.get("sagadyssey:wilderness"));n.setNoAi(true);for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);return n;}
    private static void place(GameTestHelper h,NpcBase n,BlockPos relative){var at=h.absolutePos(relative);n.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);n.setNoGravity(false);n.setOnGround(true);}
}
