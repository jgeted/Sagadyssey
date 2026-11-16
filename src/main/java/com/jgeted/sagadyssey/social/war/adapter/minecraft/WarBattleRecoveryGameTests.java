package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Stage;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarProjectileSafety;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarWeaponSwitchAdapter;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts.ChangeStatus;
import com.jgeted.sagadyssey.social.work.WorkCommitmentRuntime;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.WitnessedItemEndpoint;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import java.nio.file.*;
import java.util.*;

/** Actual chunk and process lifecycle, using production muster/field bindings and the original world files. */
@GameTestHolder("sagadyssey_war_recovery") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarBattleRecoveryGameTests {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.recovery.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.recovery.phase","");}
    private static boolean resumed(){return phase().endsWith("resume");}
    private static Path root(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT);}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent event){
        if(!enabled()||!(event.getServer() instanceof GameTestServer))return;var server=event.getServer();try{
            if(resumed()){
                var saved=read(root(server).resolve("war-live-proof.json"));
                server.getWorldData().overworldData().setGameTime(saved.get("stoppedGameTime").getAsLong());server.getWorldData().overworldData().setDayTime(saved.get("stoppedDayTime").getAsLong());
                if(phase().equals("revoked-resume"))revoke(server,saved);
            }
            server.overworld().getStructureManager().getOrCreate(net.minecraft.resources.Identifier.parse("sagadyssey_war_recovery:empty"))
                    .load(server.overworld().holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,4,16],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
        }catch(Exception failure){throw new IllegalStateException("active battle bootstrap",failure);}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){if(enabled()&&phase().endsWith("save"))try{
        var path=root(event.getServer()).resolve("war-live-proof.json");if(!Files.isRegularFile(path))return;var proof=read(path);
        var level=NbtIo.readCompressed(root(event.getServer()).resolve("level.dat"),NbtAccounter.create(16L*1024*1024)).getCompoundOrEmpty("Data");
        proof.addProperty("stoppedGameTime",level.getLongOr("Time",0));proof.addProperty("stoppedDayTime",level.getLongOr("DayTime",0));write(path,proof);
    }catch(Exception failure){throw new IllegalStateException("actual stopped clock",failure);}}
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_recovery",phase(),"sagadyssey_war_recovery:empty",8000,0,true,phase().equals("self-equip")?WarBattleRecoveryGameTests::selfEquip:resumed()?WarBattleRecoveryGameTests::resume:WarBattleRecoveryGameTests::fresh)):List.of();}

    private static void selfEquip(GameTestHelper h){
        var server=h.getLevel().getServer();var rights=RightsStateData.get(server);var people=new ArrayList<NpcBase>();
        var kinds=List.of(WarAttackKind.SWORD_STRIKE,WarAttackKind.BOW_SHOT,WarAttackKind.CROSSBOW_SHOT);
        var weapons=List.of(Items.IRON_SWORD,Items.BOW,Items.CROSSBOW);var contract=UUID.randomUUID();
        for(int i=0;i<4;i++){
            h.setBlock(new BlockPos(2+i*3,0,2),Blocks.STONE);var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(2+i*3,1,2));
            n.setProfession(NpcProfession.WORKER);n.setCommand(NpcCommand.STAY);for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);
            n.getPersonalInventory().clearContent();n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));
            n.getPersonalInventory().setItem(0,new ItemStack(weapons.get(i%3)));people.add(n);
            var person=SubjectRef.character(n.getCharacterId());var owner=i==3?SubjectRef.character(UUID.randomUUID()):person;
            rights.ensureContext(WitnessedItemEndpoint.personal(n).id(),"fixture:original_bag",person,person,AccessPolicy.PRIVATE);
            rights.ensureContext(WitnessedItemEndpoint.equipment(n).id(),"fixture:original_gear",person,person,AccessPolicy.PRIVATE);
            rights.putClaim(new AssetClaim(UUID.randomUUID(),WitnessedItemEndpoint.personal(n).id(),0,ItemFingerprint.of(n.getPersonalInventory().getItem(0)),1,owner,person,Set.of(),Set.of(),"fixture:original_title",0));
        }
        var adapter=new WarWeaponSwitchAdapter();var recovered=new WarWeaponSwitchAdapter();
        h.startSequence().thenWaitUntil(()->{
            for(int i=0;i<3;i++){
                var n=people.get(i);var a=adapter.arms(n,contract,kinds.get(i));
                if(a.status()==ChangeStatus.PENDING)adapter.equip(n,contract,kinds.get(i),kinds.get(i));
                h.assertTrue(adapter.arms(n,contract,kinds.get(i)).status()==ChangeStatus.READY&&n.getMainHandItem().is(weapons.get(i)),"original carried primary is really equipped through shared transfer: "+n.getWorkActivityDetail());
            }
        }).thenWaitUntil(()->{
            for(int i=0;i<3;i++){
                var n=people.get(i);recovered.settle(n);
                h.assertTrue(recovered.arms(n,contract,kinds.get(i)).status()==ChangeStatus.READY,"a fresh adapter re-verifies the original physical receipt");
            }
        }).thenExecute(()->{
            for(int i=0;i<3;i++){
                var n=people.get(i);var weapon=weapons.get(i);for(int j=0;j<10;j++)h.assertTrue(recovered.equip(n,contract,kinds.get(i),kinds.get(i)).status()==ChangeStatus.READY,"repeated equip is already complete");
                h.assertTrue(n.getProfession()==NpcProfession.WORKER&&n.getCommand()==NpcCommand.STAY&&n.getPersonalInventory().count(s->s.is(Items.IRON_PICKAXE))==1&&n.getPersonalInventory().count(s->s.is(weapon))==0,"private tool and original civilian role conserved");
            }
            var foreign=people.get(3);var denied=adapter.equip(foreign,contract,WarAttackKind.SWORD_STRIKE,WarAttackKind.SWORD_STRIKE);h.assertTrue(denied.status()==ChangeStatus.BLOCKED,"carried foreign property is not self-owned arming authority: "+denied+" bag="+rights.context(WitnessedItemEndpoint.personal(foreign).id())+" body="+CharacterRegistryData.get(server).bodyOf(foreign.getCharacterId()));
            h.assertTrue(foreign.getMainHandItem().is(Items.IRON_PICKAXE)&&foreign.getPersonalInventory().getItem(0).is(Items.IRON_SWORD)&&!foreign.getPersistentData().contains(WarWeaponSwitchAdapter.KEY),"foreign item and original equipment left intact");
            var proof=new JsonObject();proof.addProperty("primaryKinds",3);proof.addProperty("realRelocation",true);proof.addProperty("privateToolsPreserved",true);proof.addProperty("freshAdapterVerified",true);proof.addProperty("foreignPropertyDenied",true);write(root(server).resolve("war-self-equip-proof.json"),proof);
        }).thenSucceed();
    }

    private static final class Scene {
        final GameTestHelper h;final ServerLevel level;final MinecraftServer server;final BlockPos origin;
        ServerPlayer player;UUID actor,army,guardUnit,bowUnit,encounter;int watching=-1;final List<UUID> contracts=new ArrayList<>();
        final List<NpcBase> people=new ArrayList<>();NpcBase foe;JsonObject saved=new JsonObject();
        Scene(GameTestHelper h,BlockPos origin){this.h=h;this.level=h.getLevel();this.server=level.getServer();this.origin=origin;}
        BlockPos at(int x,int y,int z){return origin.offset(x,y,z);}
        WarServerRuntime core(){return WarServerEvents.existing(server).orElseThrow();}
        WarFieldRuntime field(){return core().field().orElseThrow();}
        WarMusterRuntime muster(){return WarMusterRuntime.existing(server).orElseThrow();}
        NpcBase soldier(int index){return people.get(index);}
    }
    private static void fresh(GameTestHelper h){
        var test=h.absolutePos(BlockPos.ZERO);var s=new Scene(h,new BlockPos((test.getX()+2048)>>4<<4,test.getY()-3,test.getZ()>>4<<4));force(s,true);
        for(int x=-2;x<18;x++)for(int z=-2;z<18;z++){h.setBlock(h.relativePos(s.at(x,0,z)),Blocks.STONE);for(int y=1;y<8;y++)h.setBlock(h.relativePos(s.at(x,y,z)),Blocks.AIR);}
        s.player=WarMusterGameTests.battlePlayer(h,"RecoveryLord",h.relativePos(s.at(5,1,3)));s.actor=CharacterRegistry.currentId(s.player);
        for(int i=0;i<2;i++){var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),h.relativePos(s.at(4+i*6,1,5)));place(n,s.at(4+i*6,1,5));n.setProfession(NpcProfession.WORKER);n.setCommand(NpcCommand.STAY);
            for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));n.getPersonalInventory().setItem(3,new ItemStack(i==0?Items.IRON_SWORD:Items.BOW));
            n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,8));if(i==1){n.getPersonalInventory().setItem(1,new ItemStack(Items.ARROW,64));n.getPersonalInventory().setItem(2,new ItemStack(Items.IRON_SWORD));}
            NpcLifeData.get(s.server).onRecruited(n,s.actor,s.level.getGameTime());NpcLifeData.get(s.server).setHunger(n.getCharacterId(),300,s.level.getGameTime());s.people.add(n);
            pen(s,4+i*6,true);
        }
        s.foe=h.spawn(NpcEntityTypes.NPC_BASE.get(),h.relativePos(s.at(13,1,13)));place(s.foe,s.at(13,1,13));s.foe.setProfession(NpcProfession.HEAVY);s.foe.setFaction(FactionRegistry.get("sagadyssey:wilderness"));s.foe.setNoAi(true);for(var slot:EquipmentSlot.values())s.foe.setItemSlot(slot,ItemStack.EMPTY);
        s.foe.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);s.foe.setHealth(1024); // explicit initial fixture durability, never healed
        s.player.getInventory().setItem(3,new ItemStack(Items.EMERALD,16));WarMusterGameTests.command(s.player,"saga war muster create RecoveryArmy");s.army=WarMusterEntry.armyId(s.actor,"RecoveryArmy");s.guardUnit=WarMusterEntry.unitId(s.army,"Guard");s.bowUnit=WarMusterEntry.unitId(s.army,"Bow");
        // Accelerated GameTest time must not outrun the asynchronous journal while the lord stands still.
        // Move only the listed fixture player alongside the actual candidate; production proximity checks remain intact.
        h.onEachTick(()->{if(s.watching>=0){var n=s.soldier(s.watching);s.player.setPos(n.getX(),n.getY(),n.getZ()+3);}for(int i=0;i<s.contracts.size();i++)if(s.muster().book().offer(s.contracts.get(i)).filter(o->o.stage()==Stage.PREPARING).isPresent()){
            var n=s.soldier(i);s.player.setPos(n.getX(),n.getY(),n.getZ()+3);s.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,n.getEyePosition());break;
        }});
        var sequence=h.startSequence().thenWaitUntil(()->h.assertTrue(s.muster().book().owns(s.actor,s.army),"army durable"))
                .thenExecute(()->{WarMusterGameTests.command(s.player,"saga war muster unit RecoveryArmy Guard sword");WarMusterGameTests.command(s.player,"saga war muster unit RecoveryArmy Bow bow");})
                .thenWaitUntil(()->h.assertTrue(List.of(s.guardUnit,s.bowUnit).stream().allMatch(id->s.muster().book().unit(id).filter(u->u.recorded()&&s.muster().book().durable(u)).isPresent()),"two units durable"));
        if(phase().equals("preview")){preview(s,sequence);return;}
        sequence=sequence.thenExecute(()->{for(int i=0;i<2;i++)s.contracts.add(WarMusterGameTests.battleOffer(h,s.player,s.soldier(i),"RecoveryArmy",i==0?"Guard":"Bow"));s.player.setPos(s.at(5,1,3).getCenter());})
                .thenWaitUntil(()->h.assertTrue(s.contracts.size()==2&&s.contracts.stream().allMatch(id->s.muster().book().offer(id).filter(o->o.stage()==Stage.ACTIVE&&s.muster().book().durable(o)).isPresent()),"original consent and labor active: "+s.muster().status(s.actor)+" player="+s.player.position()+" NPCs="+s.people.stream().map(n->n.position()+" distance="+s.player.distanceTo(n)+" visible="+s.player.hasLineOfSight(n)).toList()))
                .thenExecute(()->{pen(s,4,false);pen(s,10,false);place(s.soldier(0),s.at(4,1,5));place(s.soldier(1),s.at(7,1,5));s.player.setPos(s.at(5,1,3).getCenter());for(var n:s.people){n.setFaction(FactionRegistry.get("sagadyssey:kingdom"));NpcLifeData.get(s.server).setHunger(n.getCharacterId(),650,s.level.getGameTime());}politics(s.server,PoliticalRelation.NEUTRAL);var map=MapItem.create(s.level,s.at(8,1,8).getX(),s.at(8,1,8).getZ(),(byte)0,true,true);s.player.setItemSlot(EquipmentSlot.MAINHAND,map);Arrays.fill(MapItem.getSavedData(map,s.level).colors,(byte)40);})
                .thenWaitUntil(()->h.assertTrue(s.field().route(s.actor,s.army,s.guardUnit).isPresent()&&s.field().route(s.actor,s.army,s.bowUnit).isPresent(),"actual own reports exist before map preview: "+s.field().reports(s.actor,s.army)+" phase="+s.core().session().orElseThrow().phase()))
                .thenExecute(()->issue(s,s.guardUnit,s.at(8,1,11)))
                .thenWaitUntil(()->holding(s,s.guardUnit)).thenExecute(()->issue(s,s.bowUnit,s.at(8,1,3)))
                .thenWaitUntil(()->holding(s,s.bowUnit)).thenWaitUntil(()->{
                    h.assertTrue(s.people.stream().allMatch(n->n.getPersonalInventory().count(stack->stack.is(Items.BREAD))<8),"actual initial meal");var ports=s.core().nativeMobilization().orElseThrow();
                    h.assertTrue(s.contracts.stream().allMatch(id->ports.provisions().find(id).filter(p->p.pending().isEmpty()&&ports.provisions().durable(id)).isPresent()),"original meals durably acknowledged before combat snapshot");
                }).thenExecute(()->{place(s.foe,s.at(8,1,10));if(phase().equals("defense-save"))h.assertTrue(s.soldier(0).hurtServer(s.level,s.level.damageSources().mobAttack(s.foe),1),"actual original attack");else politics(s.server,PoliticalRelation.WAR);})
                .thenWaitUntil(()->{var arrow=arrow(s);h.assertTrue(arrow.isPresent(),"actual in-flight military arrow: "+s.field().reports(s.actor,s.army));var permit=WarProjectileSafety.read(arrow.orElseThrow()).orElseThrow();h.assertTrue(s.field().mayImpact(permit,s.foe),"fresh current source permits arrow");s.encounter=permit.encounter();h.assertTrue(CombatEncounterData.get(s.server).encounter(s.encounter).activeAt(s.level.getGameTime()),"save/unload begins during actual active battle");if(phase().equals("defense-save"))h.assertTrue(s.field().cohesion(s.actor,s.army,s.guardUnit).filter(c->c.organization()<WarCohesion.MAX_ORGANIZATION&&!c.impacts().isEmpty()).isPresent(),"real wound has a durable original organization impact before saving");capture(s,permit).entrySet().forEach(e->s.saved.add(e.getKey(),e.getValue()));});
        if(phase().startsWith("unload"))unload(s,sequence);else sequence.thenExecute(()->{write(root(s.server).resolve("war-live-proof.json"),s.saved);s.server.saveEverything(false,true,true);}).thenSucceed();
    }
    private static void preview(Scene s,GameTestSequence seq){
        WarMusterRuntime.Proposal[] offered=new WarMusterRuntime.Proposal[2];
        seq.thenExecute(()->{for(int i=0;i<2;i++){var n=s.soldier(i);s.player.setPos(n.getX(),n.getY(),n.getZ()+3);offered[i]=s.muster().propose(s.player,n,"RecoveryArmy",i==0?"Guard":"Bow",1,1);}s.watching=0;})
                .thenIdle(700).thenExecute(()->{
                    s.h.assertTrue(s.muster().book().offer(offered[0].ticket()).isEmpty()&&s.muster().book().offer(offered[1].ticket()).isEmpty(),"preview alone creates no recorded consent or service");
                    s.soldier(0).getRandom().setSeed(0);var r=s.muster().confirm(s.player,offered[0].ticket());s.h.assertTrue(r.accepted(),"normal reading beyond thirty seconds remains confirmable");s.contracts.add(offered[0].ticket());s.watching=-1;
                }).thenIdle(1_800).thenExecute(()->{
                    var n=s.soldier(1);s.player.setPos(n.getX(),n.getY(),n.getZ()+3);var r=s.muster().confirm(s.player,offered[1].ticket());s.h.assertTrue(!r.accepted(),"a proposal beyond the two-minute window still expires");
                    s.h.assertTrue(s.muster().book().offer(offered[1].ticket()).isEmpty()&&ServiceRelationshipData.get(s.server).relationship(offered[1].ticket())==null,"expired preview cannot create a military grant");
                    var receipt=new JsonObject();receipt.addProperty("readingDelayAccepted",true);receipt.addProperty("expiredDenied",true);receipt.addProperty("previewIsNotConsent",true);write(root(s.server).resolve("war-preview-proof.json"),receipt);
                }).thenSucceed();
    }
    private static void resume(GameTestHelper h){try{
        var server=h.getLevel().getServer();var p=read(root(server).resolve("war-live-proof.json"));h.assertTrue(p.get("process").getAsLong()!=ProcessHandle.current().pid(),"new actual JVM");var s=fromSaved(h,p);force(s,true);
        s.player=WarMusterGameTests.restoreBattlePlayer(h,profile(p),s.at(5,1,3));h.assertTrue(CharacterRegistry.currentId(s.player).equals(s.actor),"same original current player life loaded from actual player file");
        var seq=h.startSequence().thenWaitUntil(()->{load(s,p);h.assertTrue(s.core().nativeMobilization().isPresent(),"production bootstrap restored");checkOriginal(s,p);});
        if(phase().equals("revoked-resume")){quiet(s,seq,p,true);return;}
        seq=seq.thenWaitUntil(()->h.assertTrue(s.contracts.stream().allMatch(id->s.muster().book().offer(id).filter(o->o.stage()==Stage.ACTIVE&&s.core().servicePermits(o.request(),s.level.getGameTime())).isPresent()),"exact original agreements still active"));
        if(phase().equals("defense-resume")){quiet(s,seq,p,false);return;}
        continuing(s,seq,p);
    }catch(Exception failure){throw new IllegalStateException("active battle resume",failure);}}
    private static void continuing(Scene s,GameTestSequence seq,JsonObject p){
        seq.thenWaitUntil(()->{var e=CombatEncounterData.get(s.server).encounter(s.encounter);s.h.assertTrue(e!=null&&e.activeAt(s.level.getGameTime())&&e.events().size()>p.getAsJsonArray("events").size(),"same active encounter progresses after fresh validation");
            long savedShot=JSON.fromJson(p.get("permit"),WarShotPermit.class).firedAt();
            s.h.assertTrue(arrow(s).filter(a->{var shot=WarProjectileSafety.read(a).orElseThrow();return shot.encounter().equals(s.encounter)&&shot.firedAt()>savedShot;}).isPresent(),"same original bow body fires a genuinely new shot, not its reloaded in-flight arrow");
            s.h.assertTrue(s.soldier(1).getPersonalInventory().count(st->st.is(Items.ARROW))<p.get("ammo").getAsInt(),"actual new arrows consumed, no catch-up volley or refill");})
            .thenExecute(()->{checkOriginal(s,p);politics(s.server,PoliticalRelation.FRIENDLY);}).thenExecute(()->retire(s))
            .thenWaitUntil(()->ended(s)).thenExecute(()->proof(s,"continued-original-battle",p)).thenSucceed();
    }
    private static void quiet(Scene s,GameTestSequence seq,JsonObject p,boolean revoked){
        int[] ammo={0};Set<UUID> events=new HashSet<>();
        seq.thenExecute(()->{ammo[0]=s.soldier(1).getPersonalInventory().count(st->st.is(Items.ARROW));for(var e:CombatEncounterData.get(s.server).encounter(s.encounter).events())events.add(e.id());
            var old=JSON.fromJson(p.get("permit"),WarShotPermit.class);s.h.assertTrue(!s.field().mayImpact(old,s.foe),"old military encounter cannot recreate lost assault or revoked service");})
            .thenIdle(160).thenExecute(()->{s.h.assertTrue(s.soldier(1).getPersonalInventory().count(st->st.is(Items.ARROW))==ammo[0],"no unauthorized arrows or replay after reload");
                s.h.assertTrue(CombatEncounterData.get(s.server).encounter(s.encounter).events().stream().filter(e->e.type()==EncounterEventType.SHOT).allMatch(e->events.contains(e.id())),"no new shot authorized by the old side alone");checkOriginal(s,p);
            });
        if(revoked){seq.thenWaitUntil(()->ended(s)).thenExecute(()->proof(s,"revoked-while-absent",p)).thenSucceed();return;}
        seq.thenExecute(()->s.h.assertTrue(s.soldier(0).hurtServer(s.level,s.level.damageSources().mobAttack(s.foe),1),"new actual attack, no recreated attacker evidence"))
            .thenWaitUntil(()->s.h.assertTrue(arrow(s).isPresent(),"fresh actual assault authorizes defense again"))
            .thenExecute(()->retire(s)).thenWaitUntil(()->ended(s)).thenExecute(()->proof(s,"lost-assault-blocked-new-assault-accepted",p)).thenSucceed();
    }
    private static void unload(Scene s,GameTestSequence seq){
        var originals=new ArrayList<>(s.people);var originalFoe=s.foe;
        seq.thenExecute(()->{s.server.getPlayerList().remove(s.player);force(s,false);})
            .thenWaitUntil(()->s.h.assertTrue(!s.level.hasChunkAt(s.origin)&&originals.stream().allMatch(n->s.level.getEntity(n.getUUID())==null)&&s.level.getEntity(originalFoe.getUUID())==null,"actual native chunk and original bodies unloaded"))
            .thenExecute(()->{var e=CombatEncounterData.get(s.server).encounter(s.encounter);s.h.assertTrue(e.activeAt(s.level.getGameTime()),"actual battle remains active when its bodies unload");s.saved.add("unloadedEvents",JSON.toJsonTree(e.events().stream().map(EncounterEvent::id).toList()));if(phase().equals("unload-revoked"))revoke(s.server,s.saved);})
            .thenIdle(100).thenExecute(()->{var e=CombatEncounterData.get(s.server).encounter(s.encounter);s.h.assertTrue(e.events().stream().map(EncounterEvent::id).toList().equals(uuids(s.saved,"unloadedEvents")),"unloaded bodies do not generate combat effects");force(s,true);s.player=WarMusterGameTests.restoreBattlePlayer(s.h,profile(s.saved),s.at(5,1,3));})
            .thenWaitUntil(()->{load(s,s.saved);s.h.assertTrue(s.soldier(0)!=originals.get(0)&&s.soldier(1)!=originals.get(1)&&s.foe!=originalFoe,"actual saved entities reloaded, not retained Java bodies");checkOriginal(s,s.saved);});
        if(phase().equals("unload-revoked"))quiet(s,seq,s.saved,true);else continuing(s,seq,s.saved);
    }
    private static JsonObject capture(Scene s,WarShotPermit permit){
        var p=new JsonObject();p.addProperty("process",ProcessHandle.current().pid());p.addProperty("phase",phase());p.addProperty("origin",s.origin.asLong());p.addProperty("actor",s.actor.toString());p.addProperty("account",s.player.getUUID().toString());p.addProperty("name",s.player.getGameProfile().name());p.addProperty("army",s.army.toString());p.addProperty("guardUnit",s.guardUnit.toString());p.addProperty("bowUnit",s.bowUnit.toString());p.addProperty("encounter",s.encounter.toString());
        p.add("contracts",JSON.toJsonTree(s.contracts));p.add("bodies",JSON.toJsonTree(List.of(s.soldier(0).getUUID(),s.soldier(1).getUUID(),s.foe.getUUID())));p.add("characters",JSON.toJsonTree(List.of(s.soldier(0).getCharacterId(),s.soldier(1).getCharacterId(),s.foe.getCharacterId())));
        p.add("draws",JSON.toJsonTree(s.contracts.stream().map(id->s.muster().book().offer(id).orElseThrow().draw()).toList()));p.add("probabilities",JSON.toJsonTree(s.contracts.stream().map(id->s.muster().book().offer(id).orElseThrow().probability()).toList()));
        p.add("events",JSON.toJsonTree(CombatEncounterData.get(s.server).encounter(s.encounter).events().stream().map(EncounterEvent::id).toList()));p.add("permit",JSON.toJsonTree(permit));p.addProperty("ammo",s.soldier(1).getPersonalInventory().count(st->st.is(Items.ARROW)));p.add("bread",JSON.toJsonTree(s.people.stream().map(n->n.getPersonalInventory().count(st->st.is(Items.BREAD))).toList()));
        p.add("cohesion",JSON.toJsonTree(List.of(s.field().cohesion(s.actor,s.army,s.guardUnit).orElseThrow(),s.field().cohesion(s.actor,s.army,s.bowUnit).orElseThrow())));return p;
    }
    private static void checkOriginal(Scene s,JsonObject p){
        var bodies=uuids(p,"bodies");var characters=uuids(p,"characters");var registry=CharacterRegistryData.get(s.server);var all=new ArrayList<>(s.people);all.add(s.foe);
        for(int i=0;i<3;i++){var n=all.get(i);s.h.assertTrue(n.getUUID().equals(bodies.get(i))&&n.getCharacterId().equals(characters.get(i))&&registry.bodyOf(n.getCharacterId()).entityId().equals(n.getUUID()),"same original person and exact body binding");}
        for(int i=0;i<2;i++){var o=s.muster().book().offer(s.contracts.get(i)).orElseThrow();s.h.assertTrue(o.body().equals(bodies.get(i))&&o.draw()==p.getAsJsonArray("draws").get(i).getAsDouble()&&o.probability()==p.getAsJsonArray("probabilities").get(i).getAsDouble(),"same original choice and service, no resampling");
            s.h.assertTrue(s.soldier(i).getPersonalInventory().count(st->st.is(Items.BREAD))==p.getAsJsonArray("bread").get(i).getAsInt(),"original meal not replayed within the same ration period");}
        var actual=CombatEncounterData.get(s.server).encounter(s.encounter);s.h.assertTrue(actual!=null&&actual.events().stream().limit(p.getAsJsonArray("events").size()).map(EncounterEvent::id).toList().equals(uuids(p,"events")),"original event prefix preserved");
        s.h.assertTrue(actual.events().stream().map(EncounterEvent::id).distinct().count()==actual.events().size(),"no duplicate original combat event");
        for(var saved:p.getAsJsonArray("cohesion")){
            var expected=saved.getAsJsonObject();var current=s.field().cohesion(s.actor,s.army,UUID.fromString(expected.get("unit").getAsString())).orElseThrow();
            s.h.assertTrue(current.revision()>=expected.get("revision").getAsLong()&&current.order().toString().equals(expected.get("order").getAsString())&&current.sequence()==expected.get("sequence").getAsLong(),"original organization lineage and received order survive");
            s.h.assertTrue(current.organization()<=expected.get("organization").getAsInt(),"no free organization reset or unseen recovery across the observation gap");
            var json=JSON.toJsonTree(current).getAsJsonObject();for(var impact:expected.getAsJsonObject("impacts").entrySet())s.h.assertTrue(impact.getValue().equals(json.getAsJsonObject("impacts").get(impact.getKey())),"original casualty loss is preserved, not replayed or erased");
        }
    }
    private static void load(Scene s,JsonObject p){var bodies=uuids(p,"bodies");s.people.clear();for(int i=0;i<3;i++){var e=s.level.getEntity(bodies.get(i));s.h.assertTrue(e instanceof NpcBase,"load exact saved NPC body");if(i<2)s.people.add((NpcBase)e);else s.foe=(NpcBase)e;}}
    private static Scene fromSaved(GameTestHelper h,JsonObject p){var s=new Scene(h,BlockPos.of(p.get("origin").getAsLong()));s.saved=p;s.actor=id(p,"actor");s.army=id(p,"army");s.guardUnit=id(p,"guardUnit");s.bowUnit=id(p,"bowUnit");s.encounter=id(p,"encounter");s.contracts.addAll(uuids(p,"contracts"));return s;}
    private static void revoke(MinecraftServer server,JsonObject p){var actor=id(p,"actor");var people=uuids(p,"characters");var ids=uuids(p,"contracts");for(int i=0;i<2;i++)ServiceRelationshipData.get(server).endDelegation(ids.get(i),actor,people.get(i),"original exact delegation revoked while absent",server.overworld().getGameTime());politics(server,PoliticalRelation.FRIENDLY);}
    private static void retire(Scene s){for(var id:s.contracts)WarMusterGameTests.command(s.player,"saga war muster retire "+id);}
    private static void ended(Scene s){s.h.assertTrue(s.contracts.stream().allMatch(id->s.muster().book().offer(id).filter(o->o.stage()==Stage.ENDED&&s.muster().book().durable(o)).isPresent()),"original military labor released");s.h.assertTrue(s.people.stream().noneMatch(WorkCommitmentRuntime::blocksCivil),"civil labor available after release");s.h.assertTrue(s.player.getInventory().countItem(Items.EMERALD)==14&&s.people.stream().allMatch(n->n.getPersonalInventory().count(st->st.is(Items.EMERALD))==1),"original actual inventory pays once per contract; no duplicate payroll");s.h.assertTrue(s.people.stream().allMatch(n->n.getProfession()==NpcProfession.WORKER&&n.getCommand()==NpcCommand.STAY&&n.getPersonalInventory().count(st->st.is(Items.IRON_PICKAXE))==1),"original civilian mode and private tool preserved after arming and release");}
    private static void proof(Scene s,String outcome,JsonObject p){var r=new JsonObject();r.addProperty("process",ProcessHandle.current().pid());r.addProperty("savedProcess",p.get("process").getAsLong());r.addProperty("phase",phase());r.addProperty("outcome",outcome);r.addProperty("originalPeopleAndBodies",true);r.addProperty("onePersistedChoice",true);r.addProperty("originalMealAndPayroll",true);r.addProperty("headlessNoOpWire",true);r.addProperty("normalClientAccepted",false);r.add("originalCapture",p);r.addProperty("payerEmeralds",s.player.getInventory().countItem(Items.EMERALD));r.addProperty("actualArrowsRemaining",s.soldier(1).getPersonalInventory().count(st->st.is(Items.ARROW)));r.addProperty("actualNpcEmeralds",s.people.stream().mapToInt(n->n.getPersonalInventory().count(st->st.is(Items.EMERALD))).sum());write(root(s.server).resolve("war-recovery-result.json"),r);}
    private static Optional<AbstractArrow> arrow(Scene s){return s.level.getEntitiesOfClass(AbstractArrow.class,s.soldier(1).getBoundingBox().inflate(24),a->a.getOwner()==s.soldier(1)&&a.getDeltaMovement().lengthSqr()>.001&&WarProjectileSafety.read(a).filter(p->s.field().mayImpact(p,s.foe)).isPresent()).stream().findFirst();}
    private static void holding(Scene s,UUID unit){s.h.assertTrue(s.field().reports(s.actor,s.army).stream().anyMatch(r->r.unit().equals(unit)&&r.state()==WarFieldReport.State.HOLDING),"actual ordered body reaches station: "+s.field().reports(s.actor,s.army));}
    private static void force(Scene s,boolean on){for(int x=(s.origin.getX()>>4)-1;x<=(s.origin.getX()>>4)+1;x++)for(int z=(s.origin.getZ()>>4)-1;z<=(s.origin.getZ()>>4)+1;z++)s.level.setChunkForced(x,z,on);}
    private static void place(NpcBase n,BlockPos at){n.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);n.setNoGravity(false);n.setOnGround(true);}
    private static void pen(Scene s,int x,boolean present){for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)s.h.setBlock(s.h.relativePos(s.at(x+dx,1,5+dz)),present?Blocks.OAK_FENCE:Blocks.AIR);}
    private static void politics(MinecraftServer server,PoliticalRelation relation){PoliticalStateData.get(server).setRelation(server,"sagadyssey:kingdom","sagadyssey:wilderness",relation,server.overworld().getGameTime());}
    private static void issue(Scene s,UUID unit,BlockPos at){var maps=WarMapRuntime.get(s.server);var v=maps.begin(s.player).view().orElseThrow();var p=maps.act(s.player,new Request(v.session(),v.revision(),Action.PREVIEW,0,unit,WarMapView.Mode.HOLD,(int)Math.round(v.sheet().pixelX(at.getX())),(int)Math.round(v.sheet().pixelZ(at.getZ())),new UUID(0,0))).view().orElseThrow();s.h.assertTrue(p.preview().isPresent(),"original map preview");var r=maps.act(s.player,new Request(p.session(),p.revision(),Action.ISSUE,0,unit,WarMapView.Mode.HOLD,0,0,p.preview().orElseThrow().ticket())).view().orElseThrow().receipt().orElseThrow();s.h.assertTrue(r.outcome()==WarMapView.Outcome.ACCEPTED,"original map order: "+r);}
    private static GameProfile profile(JsonObject p){return new GameProfile(id(p,"account"),p.get("name").getAsString());}
    private static UUID id(JsonObject p,String key){return UUID.fromString(p.get(key).getAsString());}
    private static List<UUID> uuids(JsonObject p,String key){return p.getAsJsonArray(key).asList().stream().map(v->UUID.fromString(v.getAsString())).toList();}
    private static JsonObject read(Path p){try{return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}catch(Exception e){throw new IllegalStateException(e);}}
    private static void write(Path p,JsonObject v){try{Files.writeString(p,JSON.toJson(v));}catch(Exception e){throw new IllegalStateException(e);}}
}
