package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.war.api.WarFieldReport;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Stage;
import com.jgeted.sagadyssey.social.work.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.nio.file.*;
import java.util.*;

/** Headless continuation of an untouched stopped-client world copy. This is not mouse/socket acceptance. */
@GameTestHolder("sagadyssey_war_clientcopy") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarClientCopyGameTests {
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.clientcopy.test");}
    private static JsonObject source(){try{return JsonParser.parseString(Files.readString(Path.of(System.getProperty("sagadyssey.war.clientcopy.proof")))).getAsJsonObject();}catch(Exception e){throw new IllegalStateException("stopped client proof required",e);}}
    private static UUID id(JsonObject p,String key){return UUID.fromString(p.get(key).getAsString());}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent event){
        if(!enabled()||!(event.getServer() instanceof GameTestServer))return;
        var p=source();var s=event.getServer();s.getWorldData().overworldData().setGameTime(p.get("gameTime").getAsLong());s.getWorldData().overworldData().setDayTime(p.get("dayTime").getAsLong());
        try{s.overworld().getStructureManager().getOrCreate(net.minecraft.resources.Identifier.parse("sagadyssey_war_clientcopy:empty")).load(s.overworld().holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));}catch(Exception e){throw new IllegalStateException(e);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_clientcopy","existing_real_client_save","sagadyssey_war_clientcopy:empty",3000,0,true,WarClientCopyGameTests::continueCopy)):List.of();}
    private static void continueCopy(GameTestHelper h){
        var p=source();var l=h.getLevel();var server=l.getServer();var at=p.getAsJsonArray("npcPosition");
        var center=BlockPos.containing(at.get(0).getAsDouble(),at.get(1).getAsDouble(),at.get(2).getAsDouble());
        for(int x=(center.getX()>>4)-1;x<=(center.getX()>>4)+1;x++)for(int z=(center.getZ()>>4)-1;z<=(center.getZ()>>4)+1;z++)l.setChunkForced(x,z,true);
        var player=WarMusterGameTests.restoreBattlePlayer(h,new GameProfile(id(p,"account"),p.get("name").getAsString()),center);
        var actor=id(p,"actor");var army=id(p,"army");var unit=id(p,"unit");var contract=id(p,"contract");
        NpcBase[] npc={null},foe={null};int[] total={0},cash={0},npcCash={0};UUID[] encounter={null};
        h.startSequence().thenWaitUntil(()->{
            h.assertTrue(l.getEntity(id(p,"body")) instanceof NpcBase,"same original saved body loaded");npc[0]=(NpcBase)l.getEntity(id(p,"body"));
            var n=npc[0];var core=WarServerEvents.existing(server).orElseThrow();var muster=WarMusterRuntime.existing(server).orElseThrow();var o=muster.book().offer(contract).orElseThrow();
            h.assertTrue(actor.equals(CharacterRegistry.currentId(player))&&n.getCharacterId().equals(id(p,"character"))&&o.body().equals(n.getUUID()),"original player life, consent and body preserved");
            h.assertTrue(o.stage()==Stage.ACTIVE&&o.draw()==p.get("draw").getAsDouble()&&o.probability()==p.get("probability").getAsDouble()&&core.servicePermits(o.request(),l.getGameTime()),"original decision remains active without resampling");
            h.assertTrue(n.getProfession()==NpcProfession.WORKER&&n.getCommand()==NpcCommand.STAY&&!n.isNoAi()&&n.getMainHandItem().is(Items.IRON_SWORD),"ordinary gifted sword was equipped without changing civilian role");
            h.assertTrue(core.field().orElseThrow().reports(actor,army).stream().anyMatch(r->r.unit().equals(unit)&&r.state()==WarFieldReport.State.HOLDING),"actual saved map order reaches its station");
        }).thenExecute(()->{
            var n=npc[0];cash[0]=player.getInventory().countItem(Items.EMERALD);npcCash[0]=n.getPersonalInventory().count(s->s.is(Items.EMERALD));total[0]=cash[0]+npcCash[0];
            foe[0]=h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);foe[0].teleportTo(n.getX()+2,n.getY(),n.getZ());foe[0].setOnGround(true);foe[0].setProfession(NpcProfession.HEAVY);foe[0].setFaction(FactionRegistry.get("sagadyssey:wilderness"));foe[0].setNoAi(true);for(var slot:EquipmentSlot.values())foe[0].setItemSlot(slot,net.minecraft.world.item.ItemStack.EMPTY);
            foe[0].getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);foe[0].setHealth(40); // initial scene durability only
            h.assertTrue(n.hurtServer(l,l.damageSources().mobAttack(foe[0]),1),"one actual attack supplies current original self-defense evidence");
        }).thenWaitUntil(()->{
            var n=npc[0];h.assertTrue(foe[0].getHealth()<40,"same original worker deals actual native sword damage; state="+n.getWorkActivity()+" detail="+n.getWorkActivityDetail()+" position="+n.position()+" foe="+foe[0].position()+" health="+n.getHealth()+" lastDamage="+n.getLastDamageGameTime()+" now="+l.getGameTime()+" ally="+n.isCombatAlly(foe[0])+" reports="+WarServerEvents.existing(server).orElseThrow().field().orElseThrow().reports(actor,army));
            var data=CombatEncounterData.get(server);var e=data.encounters().stream().filter(v->v.sideOf(n.getCharacterId())!=null&&v.sideOf(foe[0].getCharacterId())!=null).findFirst();
            // A lone victim can use the original individual self-defense episode directly.
            // Army metadata is needed for enlistment, not for duplicating that episode.
            h.assertTrue(e.isPresent()&&data.encounters().size()==1,"the original self-defense episode is reused without a parallel military encounter");encounter[0]=e.orElseThrow().id();
        }).thenExecute(()->h.assertTrue(WarMusterGameTests.command(player,"saga war muster retire "+contract)==1,"ordinary retirement command on the exact original service"))
          .thenWaitUntil(()->{
            var muster=WarMusterRuntime.existing(server).orElseThrow();h.assertTrue(muster.book().offer(contract).filter(o->o.stage()==Stage.ENDED&&muster.book().durable(o)).isPresent()&&!WorkCommitmentRuntime.blocksCivil(npc[0]),"original military labor durably released");
            var provisions=WarServerEvents.existing(server).orElseThrow().nativeMobilization().orElseThrow().provisions();var c=provisions.find(contract).orElseThrow();
            h.assertTrue(c.pending().isEmpty()&&provisions.durable(contract)&&c.marks().containsKey(ServiceProvision.Step.CLOSE_PAYROLL)&&c.arrears(l.getGameTime()).signum()==0,"actual shared closing payroll acknowledged, no hidden arrears in this funded scene");
          }).thenExecute(()->{
            h.assertTrue(player.getInventory().countItem(Items.EMERALD)+npc[0].getPersonalInventory().count(s->s.is(Items.EMERALD))==total[0],"original real-player and NPC cash conserved");
            cash[0]=player.getInventory().countItem(Items.EMERALD);npcCash[0]=npc[0].getPersonalInventory().count(s->s.is(Items.EMERALD));
            h.assertTrue(WarMusterGameTests.command(player,"saga war muster retire "+contract)==1,"repeated retirement is an existing-status lookup");
          }).thenIdle(80).thenExecute(()->{
            var n=npc[0];h.assertTrue(player.getInventory().countItem(Items.EMERALD)==cash[0]&&n.getPersonalInventory().count(s->s.is(Items.EMERALD))==npcCash[0],"repeated retirement does not replay wages");
            h.assertTrue(n.getProfession()==NpcProfession.WORKER&&n.getCommand()==NpcCommand.STAY&&n.getMainHandItem().is(Items.IRON_SWORD),"original civilian context and self-owned gear retained");
            var result=new LinkedHashMap<String,Object>();result.put("process",ProcessHandle.current().pid());result.put("body",n.getUUID());result.put("contract",contract);result.put("encounter",encounter[0]);result.put("originalClientWorldCopy",true);result.put("sameOriginalDecision",true);result.put("actualSavedMapOrderReached",true);result.put("actualNativeSwordDamage",true);result.put("originalEncounterReused",true);result.put("originalLaborReleased",true);result.put("cashConserved",total[0]);result.put("playerEmeralds",cash[0]);result.put("npcEmeralds",npcCash[0]);result.put("headlessNoOpWire",true);result.put("normalClientFullFlowAccepted",false);
            try{Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("war-clientcopy-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}catch(Exception e){throw new IllegalStateException(e);}
          }).thenSucceed();
    }
}
