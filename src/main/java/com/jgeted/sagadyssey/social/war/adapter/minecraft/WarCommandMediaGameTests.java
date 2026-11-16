package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.combat.CombatEncounterData;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarCommandMedia12111;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.util.*;

/** Uses real Brigadier commands, native map useOn and native horn use, with explicit fixture-only authority. */
@GameTestHolder("sagadyssey_war_m4a") @PrefixGameTestTemplate(false)
public final class WarCommandMediaGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests(){return Boolean.getBoolean("sagadyssey.war.m4a.test")&&System.getProperty("sagadyssey.war.m4a.phase","").equals("media")?List.of(new TestFunction("war_m4a","physical_command_media","sagadyssey_war_m4a:empty",4000,0,true,WarCommandMediaGameTests::lifecycle)):List.of();}
    private static void lifecycle(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();for(int x=0;x<80;x++)for(int z=0;z<24;z++){level.getChunkAt(h.absolutePos(new BlockPos(x,0,z)));h.setBlock(new BlockPos(x,0,z),Blocks.STONE);}
        var player=player(h,"WarCommander",new BlockPos(7,1,6));var stranger=player(h,"OtherCommander",new BlockPos(4,1,15));var actor=CharacterRegistry.currentId(player);
        var original=WarCandidateGameTests.fixture(h,1,actor);var npc=original.npcs().getFirst();var source=original.requests().getFirst();
        var r=new WarMilitiaRequest(source.id(),actor,source.armyId(),source.unitId(),source.characterId(),new WarLoadout(WarLoadout.Mount.FOOT,WarLoadout.Weapon.SWORD,false,false).id(),1,source.kind(),source.role(),source.wage(),source.requestedAtTick());
        var spawn=h.absolutePos(new BlockPos(3,1,6));npc.teleportTo(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);npc.setNoGravity(false);npc.setOnGround(true);npc.setCommand(NpcCommand.STAY);npc.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);npc.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,8));npc.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));original.sources().life().setHunger(npc.getCharacterId(),100,level.getGameTime());
        boolean[] authorized={true};var core=WarServerEvents.activate(server);core.bindAuthority((a,b)->authorized[0]&&a.equals(actor)&&b.equals(r.armyId())?WarCommandService.Permission.ALLOWED:WarCommandService.Permission.DENIED);
        h.assertTrue(core.submit(new WarCommand.CreateUnit(UUID.randomUUID(),actor,r.armyId(),r.unitId(),r.loadout(),1)).status()==WarResult.Status.APPLIED,"explicit fixture formation");server.overworld().getDataStorage().saveAndJoin();
        h.setBlock(new BlockPos(3,1,18),Blocks.CHEST);h.setBlock(new BlockPos(6,1,18),Blocks.CHEST);var a=(ChestBlockEntity)level.getBlockEntity(h.absolutePos(new BlockPos(3,1,18)));var t=(ChestBlockEntity)level.getBlockEntity(h.absolutePos(new BlockPos(6,1,18)));a.setItem(0,new ItemStack(Items.IRON_SWORD));t.setItem(0,new ItemStack(Items.EMERALD,64));
        var armory=ItemLocation.block(UUID.randomUUID(),level.dimension().identifier().toString(),a.getBlockPos());var treasury=ItemLocation.block(UUID.randomUUID(),level.dimension().identifier().toString(),t.getBlockPos());armory.bindBlock(server);treasury.bindBlock(server);var rights=RightsStateData.get(server);var owner=SubjectRef.character(actor);var person=SubjectRef.character(npc.getCharacterId());rights.ensureContext(armory.id(),"test:armory",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(treasury.id(),"test:treasury",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(ItemLocation.personal(npc.getCharacterId(),npc.getUUID()).id(),"test:bag",person,person,AccessPolicy.PRIVATE);rights.ensureContext(ItemLocation.equipment(npc.getCharacterId(),npc.getUUID()).id(),"test:equipment",person,person,AccessPolicy.PRIVATE);
        try{
            var ports=core.bindNativeMobilization(original.sources());var field=core.bindFieldExecution(new WarFieldRuntime.Sources(original.sources().characters(),original.sources().injuries(),CombatEncounterData.get(server),original.sources().life()));var media=core.openCommandMedia().orElseThrow();
            var map=MapItem.create(level,player.getBlockX(),player.getBlockZ(),(byte)0,true,true);var horn=InstrumentItem.create(Items.GOAT_HORN,level.registryAccess().lookupOrThrow(Registries.INSTRUMENT).listElements().findFirst().orElseThrow());
            var flag=h.absolutePos(new BlockPos(12,1,6));UUID[] issued={null};long[] previous={0};
            h.startSequence().thenWaitUntil(()->h.assertTrue(ports.configure(r,treasury,armory,List.of(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_SWORD)),1)))==WarMobilizationPorts.Status.APPLIED,"real kit before input test"))
              .thenExecute(()->h.assertTrue(core.mobilization().orElseThrow().begin(r).outcome()==WarMobilizationService.Outcome.ACCEPTED,"original service before media"))
              .thenWaitUntil(()->h.assertTrue(core.mobilization().orElseThrow().activePerson(r.characterId()).isPresent()&&field.reports(actor,r.armyId()).size()==1,"real nearby report"))
              .thenExecute(()->{
                  h.assertTrue(command(stranger,"saga war select "+r.unitId())==0 && media.selection(stranger).isEmpty(),"even an operator command source cannot bypass current-character army authority");
                  h.assertTrue(command(player,"saga war select "+r.unitId())==1 && command(player,"saga war mode hold")==1,"real player selects a whole unit and map mode");
                  player.setItemInHand(InteractionHand.MAIN_HAND,map);click(h,player,h.absolutePos(new BlockPos(9,0,6)));previous[0]=0;
              }).thenWaitUntil(()->{
                  var u=core.session().orElseThrow().unit(r.unitId()).orElseThrow();h.assertTrue(u.currentOrder().isPresent(),"map order receives through original journal");issued[0]=u.currentOrder().orElseThrow().id();var report=field.reports(actor,r.armyId()).getFirst();h.assertTrue(report.order().filter(issued[0]::equals).isPresent()&&report.state()==WarFieldReport.State.HOLDING,"map command physically moves the original NPC");
              }).thenExecute(()->{
                  var at=h.absolutePos(new BlockPos(10,1,6));player.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);h.setBlock(new BlockPos(12,1,6),Blocks.RED_BANNER);player.setItemInHand(InteractionHand.MAIN_HAND,map);click(h,player,flag);
              }).thenWaitUntil(()->h.assertTrue(media.post(player).isPresent(),"fixed banner becomes usable only after durable commit"))
              .thenExecute(()->{
                  h.assertTrue(WarCommandMedia12111.actualPost(player,media.post(player).orElseThrow()),"actual loaded banner exists");h.assertTrue(!MapItem.getSavedData(map,level).getBanners().isEmpty(),"native map retains its banner marker");
                  previous[0]=core.session().orElseThrow().unit(r.unitId()).orElseThrow().lastIssuedSequence();player.setItemInHand(InteractionHand.MAIN_HAND,horn);var used=player.gameMode.useItem(player,level,horn,InteractionHand.MAIN_HAND);h.assertTrue(used.consumesAction()&&player.getCooldowns().isOnCooldown(horn),"one real native horn use with native cooldown");
              }).thenWaitUntil(()->{
                  var u=core.session().orElseThrow().unit(r.unitId()).orElseThrow();h.assertTrue(u.lastIssuedSequence()>previous[0],"horn dispatch accepted");h.assertTrue(u.currentOrder().filter(o->o.intent().kind().equals(com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.RALLY)).isPresent(),"horn becomes a received rally order");var report=field.reports(actor,r.armyId()).getFirst();h.assertTrue(report.state()==WarFieldReport.State.REGROUPING,"original person physically rallies at banner");
              }).thenExecute(()->{
                  previous[0]=core.session().orElseThrow().unit(r.unitId()).orElseThrow().lastIssuedSequence();player.gameMode.useItem(player,level,horn,InteractionHand.MAIN_HAND);h.assertTrue(core.session().orElseThrow().unit(r.unitId()).orElseThrow().lastIssuedSequence()==previous[0],"native horn cooldown prevents duplicate orders");h.setBlock(new BlockPos(12,1,6),Blocks.AIR);
              }).thenIdle(150).thenExecute(()->{
                  h.assertTrue(!WarCommandMedia12111.actualPost(player,media.post(player).orElseThrow()),"broken physical banner invalidates command post");player.gameMode.useItem(player,level,horn,InteractionHand.MAIN_HAND);
              }).thenIdle(10).thenExecute(()->{
                  h.assertTrue(core.session().orElseThrow().unit(r.unitId()).orElseThrow().lastIssuedSequence()==previous[0],"sounding without a real banner sends no order");h.setBlock(new BlockPos(12,1,6),Blocks.RED_BANNER);var far=h.absolutePos(new BlockPos(70,1,6));level.setChunkForced(far.getX()>>4,far.getZ()>>4,true);npc.teleportTo(far.getX()+.5,far.getY(),far.getZ()+.5);
              }).thenWaitUntil(()->h.assertTrue(field.route(actor,r.armyId(),r.unitId()).filter(route->route.channel()==WarTransmission.Channel.COURIER).isPresent(),"wait for the remote original body's actual report"))
              .thenExecute(()->{
                  h.assertTrue(!media.maySignal(player),"horn cannot silently become a distant courier");player.setItemInHand(InteractionHand.MAIN_HAND,map);click(h,player,h.absolutePos(new BlockPos(11,0,8)));
              }).thenWaitUntil(()->{
                  var u=core.session().orElseThrow().unit(r.unitId()).orElseThrow();h.assertTrue(u.lastIssuedSequence()>previous[0]&&!u.pendingOrders().isEmpty(),"distant map order is pending delivery");var pending=u.pendingOrders().getLast();var transmission=field.transmission(actor,r.armyId(),pending.id()).orElseThrow();h.assertTrue(transmission.route().channel()==WarTransmission.Channel.COURIER && transmission.received().isEmpty(),"native map uses real delayed transmission");
              }).thenExecute(()->{
                  authorized[0]=false;h.assertTrue(command(player,"saga war select "+r.unitId())==0&&media.selection(player).isEmpty(),"revoked authority invalidates existing selection");authorized[0]=true;command(player,"saga war clear");h.assertTrue(media.selection(player).isEmpty(),"explicitly leave military input mode");core.mobilization().orElseThrow().demobilize(r.id(),actor);
              }).thenWaitUntil(()->h.assertTrue(core.mobilization().orElseThrow().service().orElseThrow().find(r.id()).orElseThrow().stage()==WarMobilization.Stage.RELEASED,"native original service releases after input exercise"))
              .thenExecute(()->{
                  h.assertTrue(a.countItem(Items.IRON_SWORD)==1&&npc.getPersonalInventory().getItem(1).is(Items.IRON_PICKAXE)&&!npc.isNoAi(),"input preserves private tools and native gear return");var proof=new JsonObject();proof.addProperty("nativeMapClick",true);proof.addProperty("vanillaBannerMarker",true);proof.addProperty("nativeHornAndCooldown",true);proof.addProperty("authorityDeniedEvenForOp",true);proof.addProperty("brokenFlagDenied",true);proof.addProperty("distantHornDenied",true);proof.addProperty("distantMapCourier",true);proof.addProperty("people",1);proof.addProperty("process",ProcessHandle.current().pid());try{Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("war-m4a-media.json"),new GsonBuilder().setPrettyPrinting().create().toJson(proof));}catch(Exception e){throw new IllegalStateException(e);}
              }).thenSucceed();
        }catch(Exception e){throw new IllegalStateException("native command media fixture",e);}
    }
    private static ServerPlayer player(GameTestHelper h,String name,BlockPos pos){
        var p=FakePlayerFactory.get(h.getLevel(),new GameProfile(UUID.randomUUID(),name));var at=h.absolutePos(pos);p.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);p.setNoGravity(true);p.setGameMode(GameType.SURVIVAL);h.getLevel().addNewPlayer(p);var character=CharacterRegistry.ensureCurrent(p);CharacterRegistryData.get(h.getLevel().getServer()).bindNpcBody(character.id(),h.getLevel().dimension().identifier().toString(),p.getUUID(),h.getLevel().getGameTime());return p;
    }
    private static int command(ServerPlayer p,String text){try{return p.level().getServer().getCommands().getDispatcher().execute(text,p.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ALL).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}}
    private static void click(GameTestHelper h,ServerPlayer p,BlockPos pos){
        var aim=p.level().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.AbstractBannerBlock?Vec3.atCenterOf(pos):new Vec3(pos.getX()+.5,pos.getY()+.999,pos.getZ()+.5);
        var hit=p.level().clip(new ClipContext(p.getEyePosition(),aim,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,p));h.assertTrue(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(pos),"fixture uses a real visible block hit");
        h.assertTrue(p.gameMode.useItemOn(p,p.level(),p.getMainHandItem(),InteractionHand.MAIN_HAND,hit).consumesAction(),"native map useOn consumes actual interaction");
    }
}
