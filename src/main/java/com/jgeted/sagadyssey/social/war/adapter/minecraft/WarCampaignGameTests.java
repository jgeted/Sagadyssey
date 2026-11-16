package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.combat.CombatEncounterData;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.politics.PoliticalRelation;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarCampaignEntry.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import java.nio.file.*;
import java.util.*;
import com.mojang.authlib.GameProfile;

@GameTestHolder("sagadyssey_war_campaign") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarCampaignGameTests {
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.campaign.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.campaign.phase","logistics");}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void starting(ServerAboutToStartEvent e)throws Exception {
        if(enabled()&&Set.of("missing","missing-allowance","missing-mount").contains(phase())){var p=e.getServer().getWorldPath(LevelResource.ROOT).resolve("data/"+WarCampaignRuntime.FILE);Files.move(p,p.resolveSibling(p.getFileName()+(phase().equals("missing")?".old":".test-retained")));}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent e)throws Exception {
        if(!enabled()||!(e.getServer() instanceof GameTestServer))return;var s=e.getServer();var l=s.overworld();
        if(Set.of("resume","missing","mount-resume","offices-resume","missing-allowance","missing-mount","leadership-resume").contains(phase())){var p=read(s.getWorldPath(LevelResource.ROOT).resolve(phase().startsWith("leadership")?"war-leadership-save.json":"war-campaign-save.json"));s.getWorldData().overworldData().setGameTime(p.get("gameTime").getAsLong());s.getWorldData().overworldData().setDayTime(p.get("dayTime").getAsLong());}
        l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME,false,s);l.getGameRules().set(GameRules.SPAWN_MOBS,false,s);
        l.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_campaign:empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!enabled())return List.of();
        TestFunction test=switch(phase()){
            case "leadership-save" -> function(14000,WarCampaignGameTests::leadership);
            case "leadership-resume" -> function(8000,WarCampaignGameTests::leadershipResume);
            case "leadership-battle" -> function(5000,WarCampaignGameTests::leadershipBattle);
            case "offices","offices-save" -> function(12000,WarCampaignGameTests::offices);
            case "offices-resume" -> function(12000,WarCampaignGameTests::resume);
            case "resume","missing","mount-resume","missing-allowance","missing-mount" -> function(6000,WarCampaignGameTests::resume);
            case "mount","mount-save" -> function(15000,WarCampaignGameTests::mount);
            default -> function(6000,WarCampaignGameTests::logistics);
        };
        return List.of(test);
    }
    private static TestFunction function(int ticks,java.util.function.Consumer<GameTestHelper> test){return new TestFunction("war_campaign",phase(),"sagadyssey_war_campaign:empty",ticks,0,true,test);}
    private static BlockPos scene(GameTestHelper h){var origin=h.absolutePos(BlockPos.ZERO).offset(3072,-3,0);force(h,origin);for(int x=0;x<64;x++)for(int z=0;z<28;z++){h.getLevel().setBlockAndUpdate(origin.offset(x,0,z),Blocks.STONE.defaultBlockState());for(int y=1;y<6;y++)h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),Blocks.AIR.defaultBlockState());}return origin;}
    private static void force(GameTestHelper h,BlockPos p){for(int x=p.getX()>>4;x<=(p.getX()+64)>>4;x++)for(int z=p.getZ()>>4;z<=(p.getZ()+28)>>4;z++)h.getLevel().setChunkForced(x,z,true);}
    private static NpcBase person(GameTestHelper h,ServerPlayer p,BlockPos at,boolean sword){
        return person(h,p,at,sword,NpcProfession.WORKER);
    }
    private static NpcBase person(GameTestHelper h,ServerPlayer p,BlockPos at,boolean sword,NpcProfession profession){
        var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);n.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);n.setPersistenceRequired();n.setProfession(profession);n.setCommand(NpcCommand.STAY);n.setIdentity(NpcSocialClass.COMMONER,NpcOffice.NONE);for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);
        if(sword)n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));n.getPersonalInventory().clearContent();n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,16));n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));var life=NpcLifeData.get(h.getLevel().getServer());life.onRecruited(n,CharacterRegistry.currentId(p),h.getLevel().getGameTime());life.setHunger(n.getCharacterId(),100,h.getLevel().getGameTime());return n;
    }
    private static void command(GameTestHelper h,ServerPlayer p,String s){int result=WarMusterGameTests.command(p,s);h.assertTrue(result==1,"ordinary command: "+s+"; feedback="+WarMusterGameTests.feedback(p));}
    private static boolean active(net.minecraft.server.MinecraftServer s,UUID id){var m=WarMusterRuntime.existing(s).orElseThrow();return m.book().offer(id).filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE&&m.book().durable(o)).isPresent();}
    private static boolean unitReady(net.minecraft.server.MinecraftServer s,UUID id){var m=WarMusterRuntime.existing(s).orElseThrow();return m.book().unit(id).filter(u->u.recorded()&&m.book().durable(u)).isPresent();}
    private static UUID offer(GameTestHelper h,ServerPlayer p,NpcBase n,String army,String unit,boolean staff){try{return WarMusterGameTests.battleOffer(h,p,n,army,unit,staff);}catch(GameTestAssertException failed){throw new IllegalStateException("original offer fixture failed: "+failed.getMessage(),failed);}}
    private static boolean ended(net.minecraft.server.MinecraftServer s,UUID id){var m=WarMusterRuntime.existing(s).orElseThrow();return m.book().offer(id).filter(o->o.stage()==WarMusterEntry.Stage.ENDED&&m.book().durable(o)).isPresent();}
    private static Shipment shipment(net.minecraft.server.MinecraftServer s,UUID id){return (Shipment)WarCampaignRuntime.existing(s).orElseThrow().find(id).orElseThrow();}
    private static boolean terminal(net.minecraft.server.MinecraftServer s,UUID id){var c=WarCampaignRuntime.existing(s).orElseThrow();var v=shipment(s,id);return v.run().terminal()&&c.durable(v);}
    private static ChestBlockEntity chest(GameTestHelper h,BlockPos pos){h.getLevel().setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());return (ChestBlockEntity)h.getLevel().getBlockEntity(pos);}
    private static void near(ServerPlayer p,NpcBase n){p.setPos(n.getX(),n.getY(),n.getZ()+3);}
    private static void logistics(GameTestHelper h){try{
        var l=h.getLevel();var s=l.getServer();var o=scene(h);var p=WarMusterGameTests.battlePlayer(h,"CampaignLord",new BlockPos(2,1,8));p.setPos(o.getX()+4.5,o.getY()+1,o.getZ()+8.5);p.getInventory().setItem(3,new ItemStack(Items.EMERALD,64));
        var fighter=person(h,p,o.offset(4,1,5),false);var carrier=person(h,p,o.offset(7,1,5),true,NpcProfession.COURIER);var people=List.of(fighter,carrier);var services=new ArrayList<UUID>();var source=chest(h,o.offset(5,1,10));var target=chest(h,o.offset(29,1,10));source.setItem(0,new ItemStack(Items.BREAD,32));source.setItem(1,new ItemStack(Items.EMERALD,64));source.setItem(2,new ItemStack(Items.IRON_SWORD));for(int i=0;i<target.getContainerSize();i++)target.setItem(i,new ItemStack(Items.COBBLESTONE,64));
        command(h,p,"saga war muster create Campaign");command(h,p,"saga war muster create Destination");var army=WarMusterEntry.armyId(CharacterRegistry.currentId(p),"Campaign");var unit=WarMusterEntry.unitId(army,"Workers");UUID[] run={null},successful={null};var previous=new ArrayList<UUID>();
        var q=h.startSequence().thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().owns(CharacterRegistry.currentId(p),army)&&WarMusterRuntime.existing(s).orElseThrow().book().owns(CharacterRegistry.currentId(p),WarMusterEntry.armyId(CharacterRegistry.currentId(p),"Destination")),"ordinary armies durable"))
            .thenExecute(()->{p.setPos(target.getBlockPos().getX()+1.5,target.getBlockPos().getY(),target.getBlockPos().getZ()+.5);command(h,p,"saga war support depot Destination "+xyz(target.getBlockPos()));p.setPos(source.getBlockPos().getX()+1.5,source.getBlockPos().getY(),source.getBlockPos().getZ()+.5);command(h,p,"saga war support depot Campaign "+xyz(source.getBlockPos()));command(h,p,"saga war muster unit Campaign Workers sword");})
            .thenWaitUntil(()->h.assertTrue(WarCampaignRuntime.existing(s).orElseThrow().depot(army).isPresent()&&unitReady(s,unit),"original depots and unit durable"))
            .thenExecute(()->services.add(offer(h,p,fighter,"Campaign","Workers",false)))
            .thenWaitUntil(()->h.assertTrue(active(s,services.getFirst()),"unarmed original worker actually borrows sword from registered army depot"))
            .thenExecute(()->{h.assertTrue(source.countItem(Items.IRON_SWORD)==0&&fighter.getMainHandItem().is(Items.IRON_SWORD),"physical sword left original depot and entered original equipment");services.add(offer(h,p,carrier,"Campaign","Workers",true));})
            .thenWaitUntil(()->h.assertTrue(active(s,services.get(1)),"original dedicated carrier active"));
        if(phase().equals("provision")){
            q.thenExecute(()->{fighter.getPersonalInventory().removeItemNoUpdate(0);NpcLifeData.get(s).setHunger(fighter.getCharacterId(),550,l.getGameTime());command(h,p,"saga war support supply "+services.get(1)+" "+fighter.getCharacterId()+" minecraft:bread 8 1");run[0]=WarCampaignRuntime.existing(s).orElseThrow().entries().stream().filter(Shipment.class::isInstance).map(Shipment.class::cast).findFirst().orElseThrow().id();})
                .thenWaitUntil(()->h.assertTrue(terminal(s,run[0])&&source.countItem(Items.BREAD)==24&&carrier.getTransportInventory().isEmpty()&&fighter.getPersonalInventory().countItem(Items.BREAD)==7&&NpcLifeData.get(s).needs(fighter.getCharacterId()).hunger()<550,"original military recipient physically receives eight bread and original provision service consumes one"))
                .thenExecute(()->{for(var id:services)command(h,p,"saga war muster retire "+id);})
                .thenWaitUntil(()->h.assertTrue(services.stream().allMatch(id->ended(s,id))&&source.countItem(Items.IRON_SWORD)==1&&source.countItem(Items.EMERALD)==62,"original food, wages and equipment responsibilities complete"))
                .thenExecute(()->capture(h,p,o,people,services,army,source,target,run[0],"PROVISION_COMPLETE")).thenSucceed();return;
        }
        q.thenExecute(()->{command(h,p,"saga war support ship "+services.get(1)+" "+xyz(target.getBlockPos())+" minecraft:bread 8 2");run[0]=WarCampaignRuntime.existing(s).orElseThrow().entries().stream().filter(Shipment.class::isInstance).map(Shipment.class::cast).findFirst().orElseThrow().id();})
            .thenWaitUntil(()->h.assertTrue(carrier.getTransportInventory().countItem(Items.BREAD)==8&&shipment(s,run[0]).run().phase()==WarSupplyRun.Phase.UNLOADING&&carrier.getClothingOutfit().equals(NpcMilitaryRole.MILITARY_CARRIER.id()),"real cargo physically reached full destination and synchronized carrier clothing"))
            .thenExecute(()->{h.assertTrue(source.countItem(Items.BREAD)==24&&target.countItem(Items.BREAD)==0&&WorkCommitmentRuntime.blocksCivil(carrier),"full destination preserves goods and original exclusive labor");var stranger=WarMusterGameTests.battlePlayer(h,"CampaignOther",new BlockPos(2,1,9));boolean denied=false;try{WarCampaignRuntime.existing(s).orElseThrow().cancel(stranger,run[0],false);}catch(IllegalArgumentException expected){denied=true;}h.assertTrue(denied,"foreign OP cannot cancel owned transport");});
        if(phase().equals("save")){q.thenExecute(()->capture(h,p,o,people,services,army,source,target,run[0],"CARGO_SAVED")).thenSucceed();return;}
        if(phase().equals("replacement")){
            q.thenWaitUntil(()->h.assertTrue(shipment(s,run[0]).pending().isEmpty(),"settle actual rejected full-container intent before replacement"))
                .thenExecute(()->{l.setBlockAndUpdate(target.getBlockPos(),Blocks.AIR.defaultBlockState());chest(h,target.getBlockPos());p.setPos(target.getBlockPos().getX()+1.5,target.getBlockPos().getY(),target.getBlockPos().getZ()+.5);h.assertTrue(WarMusterGameTests.command(p,"saga war support depot Destination "+xyz(target.getBlockPos()))==0,"replacement has no original marker and cannot inherit old stock identity");})
                .thenIdle(80).thenExecute(()->{var actual=(ChestBlockEntity)l.getBlockEntity(target.getBlockPos());h.assertTrue(actual.countItem(Items.BREAD)==0&&carrier.getTransportInventory().countItem(Items.BREAD)==8&&!terminal(s,run[0]),"replaced endpoint receives no ghost delivery and actual cargo remains");command(h,p,"saga war support cancel "+run[0]);})
                .thenWaitUntil(()->h.assertTrue(terminal(s,run[0])&&source.countItem(Items.BREAD)==32&&carrier.getTransportInventory().isEmpty(),"replacement cancellation returns original cargo"))
                .thenExecute(()->{for(var id:services)command(h,p,"saga war muster retire "+id);})
                .thenWaitUntil(()->h.assertTrue(services.stream().allMatch(id->ended(s,id))&&source.countItem(Items.EMERALD)==62&&source.countItem(Items.IRON_SWORD)==1,"replacement leaves original loan and base wage settlement intact"))
                .thenExecute(()->capture(h,p,o,people,services,army,source,(ChestBlockEntity)l.getBlockEntity(target.getBlockPos()),run[0],"REPLACEMENT_RETURN")).thenSucceed();return;
        }
        if(phase().equals("retire")){
            q.thenExecute(()->command(h,p,"saga war muster retire "+services.get(1)))
                .thenWaitUntil(()->h.assertTrue(terminal(s,run[0])&&ended(s,services.get(1)),"retirement returns actual cargo before releasing original labor"))
                .thenExecute(()->{h.assertTrue(source.countItem(Items.BREAD)==32&&carrier.getTransportInventory().isEmpty()&&target.countItem(Items.BREAD)==0,"all cargo returned once, no ghost delivery");command(h,p,"saga war muster retire "+services.getFirst());})
                .thenWaitUntil(()->h.assertTrue(ended(s,services.getFirst())&&source.countItem(Items.IRON_SWORD)==1&&source.countItem(Items.EMERALD)==62,"original borrowed sword returned and original wages paid once"))
                .thenExecute(()->capture(h,p,o,people,services,army,source,target,run[0],"RETIRED_RETURN")).thenSucceed();return;
        }
        q.thenWaitUntil(()->h.assertTrue(shipment(s,run[0]).pending().isEmpty(),"wait for original failed transfer proof, not assume failure"))
            .thenExecute(()->command(h,p,"saga war support cancel "+run[0]))
            .thenWaitUntil(()->h.assertTrue(terminal(s,run[0])&&carrier.getTransportInventory().isEmpty()&&source.countItem(Items.BREAD)==32,"cancelled cargo actually returns to source"))
            .thenExecute(()->{target.clearContent();command(h,p,"saga war support ship "+services.get(1)+" "+xyz(target.getBlockPos())+" minecraft:bread 8 2");successful[0]=WarCampaignRuntime.existing(s).orElseThrow().entries().stream().filter(Shipment.class::isInstance).map(Shipment.class::cast).filter(v->!v.id().equals(run[0])).findFirst().orElseThrow().id();})
            .thenWaitUntil(()->h.assertTrue(terminal(s,successful[0])&&shipment(s,successful[0]).run().delivered()==2&&target.countItem(Items.BREAD)==16&&source.countItem(Items.BREAD)==16&&carrier.getClothingOutfit().equals(NpcMilitaryRole.STAFF_OFFICER.id()),"two actual approved trips delivered; original staff clothing returned"))
            .thenExecute(()->{h.assertTrue(carrier.getTransportInventory().isEmpty(),"no synthetic cargo remains");previous.add(run[0]);previous.add(successful[0]);});
        for(int i=0;i<2;i++)q.thenExecute(()->{command(h,p,"saga war support ship "+services.get(1)+" "+xyz(target.getBlockPos())+" minecraft:bread 1 1");successful[0]=WarCampaignRuntime.existing(s).orElseThrow().entries().stream().filter(Shipment.class::isInstance).map(Shipment.class::cast).filter(v->!previous.contains(v.id())).findFirst().orElseThrow().id();previous.add(successful[0]);}).thenWaitUntil(()->h.assertTrue(terminal(s,successful[0]),"additional actual duty completes once"));
        q.thenExecute(()->{command(h,p,"saga war staff review Campaign");var staff=WarStaffRuntime.existing(s).orElseThrow();h.assertTrue(staff.career(carrier.getCharacterId())==3,"career derives three confirmed original jobs, no free stat mutation");command(h,p,"saga war staff career Campaign "+carrier.getCharacterId());for(var id:services)command(h,p,"saga war muster retire "+id);})
            .thenWaitUntil(()->h.assertTrue(services.stream().allMatch(id->ended(s,id))&&source.countItem(Items.IRON_SWORD)==1&&source.countItem(Items.EMERALD)==62,"original equipment and wage settlement complete"))
            .thenExecute(()->{h.assertTrue(target.countItem(Items.BREAD)==18&&source.countItem(Items.BREAD)==14&&fighter.getProfession()==NpcProfession.WORKER&&carrier.getProfession()==NpcProfession.COURIER&&people.stream().allMatch(n->n.getCommand()==NpcCommand.STAY),"original goods, professions and commands conserved");capture(h,p,o,people,services,army,source,target,successful[0],"LOGISTICS_COMPLETE");}).thenSucceed();
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static void resume(GameTestHelper h){try{
        var s=h.getLevel().getServer();var root=s.getWorldPath(LevelResource.ROOT);var saved=read(root.resolve("war-campaign-save.json"));h.assertTrue(saved.get("process").getAsLong()!=ProcessHandle.current().pid(),"independent original-world JVM");var pos=saved.getAsJsonArray("origin");var origin=new BlockPos(pos.get(0).getAsInt(),pos.get(1).getAsInt(),pos.get(2).getAsInt());force(h,origin);
        var p=WarMusterGameTests.restoreBattlePlayer(h,new GameProfile(UUID.fromString(saved.get("account").getAsString()),saved.get("name").getAsString()),origin.offset(4,1,8));var bodies=saved.getAsJsonArray("bodies").asList().stream().map(v->UUID.fromString(v.getAsString())).toList();var services=saved.getAsJsonArray("services").asList().stream().map(v->UUID.fromString(v.getAsString())).toList();var people=new ArrayList<NpcBase>();var army=UUID.fromString(saved.get("army").getAsString());var task=UUID.fromString(saved.get("task").getAsString());
        var q=h.startSequence().thenWaitUntil(()->{people.clear();for(var id:bodies){var e=h.getLevel().getEntity(id);h.assertTrue(e instanceof NpcBase,"load exact original body");people.add((NpcBase)e);}h.assertTrue(services.stream().allMatch(id->active(s,id)),"same original active shared military services");});
        if(phase().equals("missing-mount")){
            q.thenWaitUntil(()->h.assertTrue(people.getFirst().getVehicle() instanceof Horse&&WarCampaignRuntime.existing(s).filter(v->!v.problem().isEmpty()).isPresent(),"same original horse evidence blocks a fresh care record when the military ledger is absent"))
                .thenIdle(80).thenExecute(()->{var n=people.getFirst();h.assertTrue(n.getPersonalInventory().countItem(Items.HAY_BLOCK)==1&&!Files.exists(root.resolve("data/"+WarCampaignRuntime.FILE))&&WarCampaignRuntime.existing(s).orElseThrow().entries().isEmpty(),"missing care never deducts hay again or writes an empty replacement ledger");h.assertTrue(((Horse)n.getVehicle()).getUUID().toString().equals(saved.get("horse").getAsString()),"same original horse retained for review");capture(h,p,origin,people,services,army,null,null,task,"MOUNT_REVIEW");}).thenSucceed();return;
        }
        if(phase().equals("mount-resume")){q.thenWaitUntil(()->h.assertTrue(people.getFirst().getVehicle() instanceof Horse&&WarCampaignRuntime.existing(s).isPresent(),"same original ridden horse and saved care"))
            .thenExecute(()->{var n=people.getFirst();h.assertTrue(n.getPersonalInventory().countItem(Items.HAY_BLOCK)==1,"restart has no extra feeding");var horse=(Horse)n.getVehicle();h.assertTrue(horse.getUUID().toString().equals(saved.get("horse").getAsString()),"original horse not respawned");})
            .thenIdle(60).thenExecute(()->{h.assertTrue(people.getFirst().getPersonalInventory().countItem(Items.HAY_BLOCK)==1,"repeated resumed care does not double consume");for(var id:services)command(h,p,"saga war muster retire "+id);})
            .thenWaitUntil(()->h.assertTrue(services.stream().allMatch(id->ended(s,id)),"same mounted service retired"))
            .thenExecute(()->capture(h,p,origin,people,services,army,null,null,task,"MOUNT_RESUMED")).thenSucceed();return;}
        if(Set.of("offices-resume","missing-allowance").contains(phase())){
            if(phase().equals("missing-allowance")){
                q.thenExecute(()->{h.assertTrue(WarCampaignRuntime.existing(s).isEmpty(),"missing campaign cannot discard original stipend promise");var legal=com.jgeted.sagadyssey.social.service.ServiceRelationshipData.get(s).relationship(task);h.assertTrue(legal!=null&&legal.obligations().getFirst().kind().equals("office_allowance_daily"),"original shared financial promise survives without campaign primary or backup");})
                 .thenIdle(60).thenExecute(()->{h.assertTrue(!Files.exists(root.resolve("data/"+WarCampaignRuntime.FILE))&&p.getInventory().countItem(Items.EMERALD)==64,"no guessed replacement book or stipend replay");capture(h,p,origin,people,services,army,null,null,task,"ALLOWANCE_REVIEW");}).thenSucceed();return;
            }
            q.thenWaitUntil(()->h.assertTrue(WarStaffRuntime.existing(s).flatMap(v->v.book().appointment(task).filter(v::effective)).isPresent()&&WarCampaignRuntime.existing(s).filter(v->v.allowanceReady(task)).isPresent(),"same original dedicated office and exact shared stipend agreement recovered"))
             .thenExecute(()->{h.assertTrue(WarStaffRuntime.existing(s).orElseThrow().book().entries().stream().filter(Appointment.class::isInstance).count()==1,"no restarted reappointment or duplicated rank");command(h,p,"saga war staff revoke "+task);})
             .thenWaitUntil(()->h.assertTrue(WarCampaignRuntime.existing(s).flatMap(v->v.find(task)).filter(Allowance.class::isInstance).map(Allowance.class::cast).filter(a->a.stop()>=0&&a.paid()==1&&a.owed(s.overworld().getGameTime()).signum()==0&&WarCampaignRuntime.existing(s).orElseThrow().durable(a)).isPresent(),"one resumed stipend pays once after original revoke"))
             .thenExecute(()->{for(var id:services)command(h,p,"saga war muster retire "+id);})
             .thenWaitUntil(()->h.assertTrue(services.stream().allMatch(id->ended(s,id))&&p.getInventory().countItem(Items.EMERALD)==11,"resumed fifty-two base wages plus one stipend conserve real cash"))
             .thenExecute(()->capture(h,p,origin,people,services,army,null,null,task,"OFFICES_RESUMED")).thenSucceed();return;
        }
        var source=(ChestBlockEntity)h.getLevel().getBlockEntity(origin.offset(5,1,10));var target=(ChestBlockEntity)h.getLevel().getBlockEntity(origin.offset(29,1,10));
        q.thenExecute(()->h.assertTrue(people.get(1).getTransportInventory().countItem(Items.BREAD)==8&&source.countItem(Items.BREAD)==24&&target.countItem(Items.BREAD)==0,"original real cargo and endpoints survived restart"));
        if(phase().equals("missing")){
            q.thenExecute(()->{boolean blocked=false;try{WarCampaignRuntime.open(s);}catch(Exception expected){blocked=true;}h.assertTrue(blocked&&WarCampaignRuntime.existing(s).isEmpty(),"missing primary with retained evidence cannot create empty campaign ledger");command(h,p,"saga war muster retire "+services.get(1));})
                .thenIdle(80).thenExecute(()->{h.assertTrue(!ended(s,services.get(1))&&people.get(1).getTransportInventory().countItem(Items.BREAD)==8&&!Files.exists(root.resolve("data/"+WarCampaignRuntime.FILE)),"unknown transport preserves cargo and original labor, no guessed replay/release");capture(h,p,origin,people,services,army,source,target,task,"REVIEW");}).thenSucceed();return;
        }
        q.thenExecute(target::clearContent)
            .thenWaitUntil(()->h.assertTrue(terminal(s,task)&&shipment(s,task).run().delivered()==2&&target.countItem(Items.BREAD)==16&&source.countItem(Items.BREAD)==16&&people.get(1).getTransportInventory().isEmpty(),"resumed exact original trips deliver once without duplicate pickup"))
            .thenExecute(()->{for(var id:services)command(h,p,"saga war muster retire "+id);})
            .thenWaitUntil(()->h.assertTrue(services.stream().allMatch(id->ended(s,id))&&source.countItem(Items.IRON_SWORD)==1&&source.countItem(Items.EMERALD)==62,"resumed original loan and wages settled once"))
            .thenExecute(()->capture(h,p,origin,people,services,army,source,target,task,"RESUMED_COMPLETE")).thenSucceed();
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static void mount(GameTestHelper h){try{
        var s=h.getLevel().getServer();var origin=scene(h);var p=WarMusterGameTests.battlePlayer(h,"CampaignRiderLord",new BlockPos(2,1,8));p.setPos(origin.getX()+4.5,origin.getY()+1,origin.getZ()+8.5);p.getInventory().setItem(3,new ItemStack(Items.EMERALD,16));var n=person(h,p,origin.offset(5,1,5),true);n.getPersonalInventory().setItem(2,new ItemStack(Items.HAY_BLOCK,2));n.setCommand(NpcCommand.FOLLOW);var horse=h.spawn(EntityType.HORSE,BlockPos.ZERO);horse.teleportTo(n.getX(),n.getY(),n.getZ());horse.setAge(0);horse.setTamed(true);horse.setOwner(p);horse.setItemSlot(EquipmentSlot.SADDLE,new ItemStack(Items.SADDLE));h.assertTrue(n.startRiding(horse,true,false),"existing native owned saddled mount");command(h,p,"saga war muster create Riders");var army=WarMusterEntry.armyId(CharacterRegistry.currentId(p),"Riders");var unit=WarMusterEntry.unitId(army,"Horse");var services=new ArrayList<UUID>();h.onEachTick(()->{if(!services.isEmpty()&&!active(s,services.getFirst()))near(p,n);});
        var q=h.startSequence().thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().owns(CharacterRegistry.currentId(p),army),"original army durable"))
            .thenExecute(()->command(h,p,"saga war muster unit Riders Horse light_cavalry"))
            .thenWaitUntil(()->h.assertTrue(unitReady(s,unit),"original horse unit"))
            .thenExecute(()->services.add(offer(h,p,n,"Riders","Horse",false)))
            .thenWaitUntil(()->h.assertTrue(active(s,services.getFirst())&&n.getPersonalInventory().countItem(Items.HAY_BLOCK)==1&&WarCampaignRuntime.existing(s).flatMap(v->v.find(horse.getUUID())).filter(WarCampaignEntry.Horse.class::isInstance).map(WarCampaignEntry.Horse.class::cast).filter(v->v.fresh(s.overworld().getGameTime())&&WarCampaignRuntime.existing(s).orElseThrow().durable(v)).isPresent(),"real hay consumption and original horse care durable"))
            .thenIdle(60).thenExecute(()->h.assertTrue(n.getPersonalInventory().countItem(Items.HAY_BLOCK)==1&&n.getVehicle()==horse,"repeated care keeps same native horse, no free or double feed"));
        if(phase().equals("mount-save")){q.thenExecute(()->capture(h,p,origin,List.of(n),services,army,null,null,horse.getUUID(),"MOUNT_SAVED")).thenSucceed();return;}
        q.thenIdle((int)com.jgeted.sagadyssey.social.war.domain.WarHorseCare.FEED_PERIOD)
            .thenWaitUntil(()->h.assertTrue(n.getPersonalInventory().countItem(Items.HAY_BLOCK)==0&&WarCampaignRuntime.existing(s).orElseThrow().horseReady(n),"a full actual feeding period consumes the second real hay block and renews original horse care"));
        q.thenExecute(()->{command(h,p,"saga war muster retire "+services.getFirst());})
            .thenWaitUntil(()->h.assertTrue(ended(s,services.getFirst())&&p.getInventory().countItem(Items.EMERALD)==15&&n.getPersonalInventory().countItem(Items.EMERALD)==1,"original mounted salary retained"))
            .thenExecute(()->capture(h,p,origin,List.of(n),services,army,null,null,horse.getUUID(),"MOUNT_COMPLETE")).thenSucceed();
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static void offices(GameTestHelper h){try{
        var s=h.getLevel().getServer();var origin=scene(h);var p=WarMusterGameTests.battlePlayer(h,"OfficeLord",new BlockPos(2,1,8));p.setPos(origin.getX()+4.5,origin.getY()+1,origin.getZ()+8.5);p.getInventory().setItem(3,new ItemStack(Items.EMERALD,64));var people=new ArrayList<NpcBase>();for(int i=0;i<52;i++)people.add(person(h,p,origin.offset(4+(i%9)*4,1,4+(i/9)*4),true));people.getLast().setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.WHITE_BANNER));var services=new ArrayList<UUID>();UUID[] office={null},flag={null};command(h,p,"saga war muster create Officers");var army=WarMusterEntry.armyId(CharacterRegistry.currentId(p),"Officers");var unit=WarMusterEntry.unitId(army,"Workers");
        var q=h.startSequence().thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().owns(CharacterRegistry.currentId(p),army),"original army"))
            .thenExecute(()->command(h,p,"saga war muster unit Officers Workers sword"))
            .thenWaitUntil(()->h.assertTrue(unitReady(s,unit),"original unit"));
        for(int i=0;i<52;i++){int index=i;q.thenExecute(()->services.add(offer(h,p,people.get(index),"Officers","Workers",index==51))).thenWaitUntil(()->h.assertTrue(active(s,services.get(index)),"real consenting person "+index+" durably active"));}
        q.thenExecute(()->command(h,p,"saga war staff review Officers"))
            .thenWaitUntil(()->h.assertTrue(WarStaffRuntime.existing(s).orElseThrow().book().establishment(army).filter(e->e.strength()==51&&WarStaffRuntime.existing(s).orElseThrow().book().durable(e)).isPresent(),"fifty-one actual combatants, dedicated staff does not inflate N"))
            .thenExecute(()->{near(p,people.getLast());var staff=WarStaffRuntime.existing(s).orElseThrow();boolean refused=false;try{staff.appoint(p,UUID.randomUUID(),army,Role.STAFF_OFFICER,Optional.empty(),people.getLast().getCharacterId(),1,Mode.REGULAR,"no prior expertise");}catch(IllegalArgumentException expected){refused=true;}h.assertTrue(refused,"formal expertise requirement is not silently bypassed");command(h,p,"saga war staff appoint Officers staff_officer "+people.getLast().getCharacterId()+" 1 acting 明确委托先行履职");office[0]=staff.book().forPerson(people.getLast().getCharacterId()).orElseThrow().id();})
            .thenWaitUntil(()->h.assertTrue(WarStaffRuntime.existing(s).orElseThrow().book().appointment(office[0]).filter(a->WarStaffRuntime.existing(s).orElseThrow().effective(a)).isPresent(),"original dedicated institution office durably active"))
            .thenExecute(()->{command(h,p,"saga war support allowance "+office[0]+" 2");command(h,p,"saga war staff advice Officers");})
            .thenWaitUntil(()->h.assertTrue(WarCampaignRuntime.existing(s).filter(v->v.allowanceReady(office[0])).isPresent(),"original supplementary payment agreement and military intent both durably confirmed"))
            ;
        if(phase().equals("offices-save")){q.thenExecute(()->capture(h,p,origin,people,services,army,null,null,office[0],"OFFICES_SAVED")).thenSucceed();return;}
        q.thenIdle(20).thenExecute(()->command(h,p,"saga war staff revoke "+office[0]))
            .thenWaitUntil(()->h.assertTrue(WarStaffRuntime.existing(s).orElseThrow().book().appointment(office[0]).filter(a->a.phase()==Phase.REVOKED).isPresent()&&WarCampaignRuntime.existing(s).flatMap(v->v.find(office[0])).filter(Allowance.class::isInstance).map(Allowance.class::cast).filter(a->a.stop()>=0&&a.paid()==1&&a.owed(s.overworld().getGameTime()).signum()==0&&WarCampaignRuntime.existing(s).orElseThrow().durable(a)).isPresent(),"revocation stops accrual, pays exactly original accrued stipend"))
            .thenExecute(()->{near(p,people.getLast());command(h,p,"saga war staff appoint Officers standard_bearer "+people.getLast().getCharacterId()+" 1 acting 持原旗履职");flag[0]=WarStaffRuntime.existing(s).orElseThrow().book().forPerson(people.getLast().getCharacterId()).orElseThrow().id();var at=origin.offset(26,1,16);h.getLevel().setBlockAndUpdate(at,Blocks.WHITE_BANNER.defaultBlockState());p.setItemSlot(EquipmentSlot.MAINHAND,MapItem.create(h.getLevel(),at.getX(),at.getZ(),(byte)0,true,true));var media=WarServerEvents.existing(s).orElseThrow().commandMedia().orElseThrow();media.select(p,unit);media.bindPost(p,new WarIntent.Position(WarKey.parse(h.getLevel().dimension().identifier().toString()),at.getX(),at.getY(),at.getZ()),WarKey.parse("minecraft:white_banner"));})
            .thenWaitUntil(()->h.assertTrue(WarStaffRuntime.existing(s).orElseThrow().book().appointment(flag[0]).filter(a->WarStaffRuntime.existing(s).orElseThrow().effective(a)).isPresent()&&people.getLast().distanceToSqr(origin.getX()+26.5,origin.getY()+1,origin.getZ()+16.5)<=9&&people.getLast().getClothingOutfit().equals(NpcMilitaryRole.STANDARD_BEARER.id()),"real original flag bearer walks to the banner with formal clothing"))
            .thenExecute(()->{for(var id:services)command(h,p,"saga war muster retire "+id);})
            .thenWaitUntil(()->h.assertTrue(services.stream().allMatch(id->ended(s,id))&&p.getInventory().countItem(Items.EMERALD)==11,"fifty-two original wages plus one separate stipend, total conserved"))
            .thenExecute(()->{h.assertTrue(people.stream().allMatch(n->n.getProfession()==NpcProfession.WORKER&&n.getSocialClass()==NpcSocialClass.COMMONER)&&people.getLast().getPersonalInventory().countItem(Items.EMERALD)==2,"same original commoner profession and exact allowance recipient");capture(h,p,origin,people,services,army,null,null,office[0],"OFFICES_COMPLETE");}).thenSucceed();
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static void leadership(GameTestHelper h) {
        var s=h.getLevel().getServer();
        var origin=scene(h);
        var p=WarMusterGameTests.battlePlayer(h,"LeadershipLord",new BlockPos(2,1,8));
        p.getInventory().setItem(0,new ItemStack(Items.STONE_SWORD));
        var people=new ArrayList<NpcBase>();
        for(int i=0;i<21;i++) people.add(person(h,p,origin.offset(4+(i%7)*4,1,4+(i/7)*4),i!=0));
        var captain=people.getFirst();
        int experience=captain.getExperience();
        captain.getPersonalInventory().setItem(2,new ItemStack(Items.ARROW,16));
        var depot=chest(h,origin.offset(4,1,23));
        depot.setItem(0,new ItemStack(Items.EMERALD,64));
        depot.setItem(1,new ItemStack(Items.IRON_SWORD));
        depot.setItem(2,new ItemStack(Items.BOW));
        var owner=CharacterRegistry.currentId(p);
        var army=WarMusterEntry.armyId(owner,"Leadership");
        var front=WarMusterEntry.unitId(army,"Line");
        var reserve=WarMusterEntry.unitId(army,"Reserve");
        var bows=WarMusterEntry.unitId(army,"Bows");
        var services=new ArrayList<UUID>();
        UUID[] order={null};
        command(h,p,"saga war muster create Leadership");
        var q=h.startSequence()
                .thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().owns(owner,army),"leadership army saved"))
                .thenExecute(()->{
                    command(h,p,"saga war muster unit Leadership Line sword");
                    command(h,p,"saga war muster unit Leadership Reserve sword");
                    command(h,p,"saga war muster unit Leadership Bows bow");
                    p.setPos(depot.getBlockPos().getX()+1.5,depot.getBlockPos().getY(),depot.getBlockPos().getZ()+.5);
                    command(h,p,"saga war support depot Leadership "+xyz(depot.getBlockPos()));
                })
                .thenWaitUntil(()->h.assertTrue(unitReady(s,front)&&unitReady(s,reserve)&&unitReady(s,bows)&&WarCampaignRuntime.existing(s).orElseThrow().depot(army).isPresent(),"durable original supply and unit scopes"));
        for(int i=0;i<21;i++){
            int index=i;
            q.thenExecute(()->services.add(offer(h,p,people.get(index),"Leadership",index<12?"Line":"Reserve",false)))
                    .thenWaitUntil(()->h.assertTrue(active(s,services.get(index)),"original person active "+index));
            if(i==19)q.thenExecute(()->command(h,p,"saga war staff review Leadership"))
                    .thenWaitUntil(()->h.assertTrue(established(s,army,20),"twenty available at first review"))
                    .thenExecute(()->{
                        near(p,captain);
                        h.assertTrue(WarMusterGameTests.command(p,"saga war staff unitappoint Leadership Line "+captain.getCharacterId()+" 1 regular Too early")==0,"captain unavailable before the army threshold");
                    });
        }
        q.thenExecute(()->command(h,p,"saga war staff review Leadership"))
                .thenWaitUntil(()->h.assertTrue(established(s,army,21),"twenty-one available at next review"))
                .thenExecute(()->{
                    near(p,captain);
                    command(h,p,"saga war staff unitappoint Leadership Line "+captain.getCharacterId()+" 1 regular Original unit captain");
                })
                .thenWaitUntil(()->h.assertTrue(WarStaffRuntime.existing(s).orElseThrow().fieldCaptain(army,front).isPresent()
                        &&captain.getClothingOutfit().equals(NpcMilitaryRole.TROOP_CAPTAIN.id()),"normal appointment changes synchronized captain outfit"))
                .thenExecute(()->{
                    near(p,captain);
                    order[0]=WarScaleGameTests.order(h,WarServerEvents.existing(s).orElseThrow(),p,army,front,
                            InitialWarDefinitions.MOVE,origin.offset(42,1,16));
                    h.assertTrue(leadershipSummary(s,captain).contains("机动 0"),"issuing the order does not grant experience");
                })
                .thenWaitUntil(()->h.assertTrue(leadershipSummary(s,captain).contains("机动 1"),"actual native maneuver awards one confirmed specialty point; "+leadershipSummary(s,captain)))
                .thenIdle(100)
                .thenExecute(()->{
                    h.assertTrue(leadershipSummary(s,captain).contains("机动 1")&&captain.getExperience()==experience,"same patrol cannot repeat and uses no native XP");
                    h.assertTrue(captain.getMainHandItem().is(Items.IRON_SWORD)&&depot.countItem(Items.IRON_SWORD)==0,"original loan active through promotion and march");
                    var proof=new JsonObject();
                    proof.addProperty("person",captain.getCharacterId().toString());proof.addProperty("body",captain.getUUID().toString());
                    proof.addProperty("account",p.getUUID().toString());proof.addProperty("actor",owner.toString());
                    proof.addProperty("service",services.getFirst().toString());proof.addProperty("order",order[0].toString());
                    proof.addProperty("army",army.toString());proof.addProperty("front",front.toString());proof.addProperty("position",depot.getBlockPos().asLong());
                    proof.addProperty("process",ProcessHandle.current().pid());proof.addProperty("experience",experience);
                    proof.addProperty("summary",leadershipSummary(s,captain));
                    leadershipProof(s,"save",proof);
                }).thenSucceed();
    }
    private static void leadershipBattle(GameTestHelper h) {
        var level=h.getLevel();
        var server=level.getServer();
        var origin=scene(h);
        var lord=WarMusterGameTests.battlePlayer(h,"TacticsLord",BlockPos.ZERO);
        CharacterRegistry.updateIdentity(lord,NpcSocialClass.COMMONER,NpcOffice.NONE,"sagadyssey:kingdom");
        lord.getInventory().setItem(3,new ItemStack(Items.EMERALD,64));
        var actor=CharacterRegistry.currentId(lord);
        var army=WarMusterEntry.armyId(actor,"Tactics");
        var line=WarMusterEntry.unitId(army,"Line");
        var reserve=WarMusterEntry.unitId(army,"Reserve");
        var soldiers=new ArrayList<NpcBase>();
        for(int i=0;i<21;i++){
            var member=person(h,lord,origin.offset(4+3*(i%7),1,3+3*(i/7)),i>=12);
            member.setFaction(FactionRegistry.get("sagadyssey:kingdom"));
            if(i<12)member.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SPEAR));
            soldiers.add(member);
        }
        var captain=soldiers.getFirst();
        var opponent=h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);
        var distant=origin.offset(45,1,14);
        opponent.teleportTo(distant.getX()+.5,distant.getY(),distant.getZ()+.5);
        opponent.setFaction(FactionRegistry.get("sagadyssey:merchant_guild"));
        opponent.setCommand(NpcCommand.STAY);opponent.setNoAi(true);
        opponent.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(180);
        opponent.setHealth(180);
        NpcLifeData.get(server).ensureRegistered(opponent,level.getGameTime());
        var politics=PoliticalStateData.get(server);
        politics.setRelation(server,"sagadyssey:kingdom","sagadyssey:merchant_guild",PoliticalRelation.NEUTRAL,level.getGameTime());
        command(h,lord,"saga war muster create Tactics");
        java.util.function.Supplier<WarServerRuntime> core=()->WarServerEvents.existing(server).orElseThrow();
        var services=new ArrayList<UUID>();
        var q=h.startSequence().thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(server).orElseThrow().book().owns(actor,army),"tactical army saved"))
                .thenExecute(()->{
                    command(h,lord,"saga war muster unit Tactics Line pike");
                    command(h,lord,"saga war muster unit Tactics Reserve sword");
                })
                .thenWaitUntil(()->h.assertTrue(unitReady(server,line)&&unitReady(server,reserve),"original specialization test units ready"));
        for(int i=0;i<soldiers.size();i++){
            int index=i;
            q.thenExecute(()->services.add(WarMusterGameTests.battleOfferFromAbove(h,lord,soldiers.get(index),"Tactics",index<12?"Line":"Reserve")))
                    .thenWaitUntil(()->h.assertTrue(active(server,services.get(index)),"original enlisted fighter "+index));
        }
        UUID[] hold={null},charge={null};
        q.thenExecute(()->command(h,lord,"saga war staff review Tactics"))
                .thenWaitUntil(()->h.assertTrue(established(server,army,21),"real army establishment"))
                .thenExecute(()->{
                    near(lord,captain);
                    command(h,lord,"saga war staff unitappoint Tactics Line "+captain.getCharacterId()+" 1 regular Native tactics");
                })
                .thenWaitUntil(()->h.assertTrue(WarStaffRuntime.existing(server).orElseThrow().fieldCaptain(army,line).isPresent(),"verified captain within original service"))
                .thenExecute(()->{
                    near(lord,captain);
                    hold[0]=WarScaleGameTests.order(h,core.get(),lord,army,line,InitialWarDefinitions.HOLD,origin.offset(22,1,14));
                })
                .thenWaitUntil(()->h.assertTrue(core.get().field().orElseThrow().reports(actor,army).stream()
                        .filter(r->r.unit().equals(line)&&r.state()==WarFieldReport.State.HOLDING).count()>=10,"company is physically stationed"))
                .thenExecute(()->{
                    h.assertTrue(leadershipSummary(server,captain).contains("坚守 0"),"holding alone does not yield a score");
                    var defender=soldiers.get(1);
                    // Controlled real health hit, to hold the casualty level stable while isolating the order.
                    h.assertTrue(defender.hurtServer(level,level.damageSources().mobAttack(opponent),3),"original hurt callback records the test assault");
                })
                .thenWaitUntil(()->h.assertTrue(leadershipSummary(server,captain).contains("坚守 1"),"safe physical hold and verified wound award defensive specialty"))
                .thenWaitUntil(()->h.assertTrue(CombatEncounterData.get(server).activeFor(opponent.getCharacterId(),level.getGameTime())==null,"original defense episode settled"))
                .thenExecute(()->{
                    near(lord,captain);
                    charge[0]=WarScaleGameTests.order(h,core.get(),lord,army,line,InitialWarDefinitions.CHARGE,distant);
                    politics.setRelation(server,"sagadyssey:kingdom","sagadyssey:merchant_guild",PoliticalRelation.WAR,level.getGameTime());
                })
                .thenWaitUntil(()->h.assertTrue(leadershipSummary(server,captain).contains("突击 1")
                        &&opponent.getHealth()<180,"native armed approach produces an original offensive contact"))
                .thenExecute(()->politics.setRelation(server,"sagadyssey:kingdom","sagadyssey:merchant_guild",PoliticalRelation.NEUTRAL,level.getGameTime()))
                .thenIdle(60)
                .thenExecute(()->{
                    var score=leadershipSummary(server,captain);
                    h.assertTrue(score.contains("机动 0")&&score.contains("突击 1")&&score.contains("坚守 1")&&score.contains("撤收 0"),"two distinct original order receipts, independently credited");
                    var originalWounds=InjuryData.get(server).woundsOf(opponent.getCharacterId(),level.getGameTime());
                    h.assertTrue(originalWounds.stream().anyMatch(w->soldiers.stream().anyMatch(n->n.getCharacterId().equals(w.attackerCharacterId()))),"offensive contact consumed an actual original injury record");
                    var proof=new JsonObject();
                    proof.addProperty("captain",captain.getCharacterId().toString());
                    proof.addProperty("hold",hold[0].toString());proof.addProperty("charge",charge[0].toString());
                    proof.addProperty("summary",score);proof.addProperty("opponentHealth",opponent.getHealth());
                    leadershipProof(server,"battle",proof);
                }).thenSucceed();
    }

    private static boolean established(net.minecraft.server.MinecraftServer s,UUID army,int n){
        var book=WarStaffRuntime.existing(s).orElseThrow().book();
        return book.establishment(army).filter(e->e.strength()==n&&book.durable(e)).isPresent();
    }
    private static String leadershipSummary(net.minecraft.server.MinecraftServer s,NpcBase n){
        return WarServerEvents.existing(s).orElseThrow().field().orElseThrow().leadershipSummary(n.getCharacterId());
    }
    private static void leadershipProof(net.minecraft.server.MinecraftServer s,String phase,JsonObject proof){
        try{write(s.getWorldPath(LevelResource.ROOT).resolve("war-leadership-"+phase+".json"),proof);}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void leadershipResume(GameTestHelper h){try{
        var s=h.getLevel().getServer();
        var prior=read(s.getWorldPath(LevelResource.ROOT).resolve("war-leadership-save.json"));
        var at=BlockPos.of(prior.get("position").getAsLong());
        var p=WarMusterGameTests.restoreBattlePlayer(h,new GameProfile(uuid(prior,"account"),"LeadershipLord"),at);
        var old=uuid(prior,"service");var actor=uuid(prior,"actor");var army=uuid(prior,"army");var unit=uuid(prior,"front");
        var captain=new NpcBase[1];var next=new UUID[1];var previousBread=new int[1];
        var q=h.startSequence().thenWaitUntil(()->{
            var body=h.getLevel().getEntity(uuid(prior,"body"));
            h.assertTrue(body instanceof NpcBase&&active(s,old),"reload original captain body and original contract");captain[0]=(NpcBase)body;
            h.assertTrue(WarStaffRuntime.existing(s).orElseThrow().fieldCaptain(army,unit).isPresent()&&captain[0].getClothingOutfit().equals(NpcMilitaryRole.TROOP_CAPTAIN.id()),"reload confirmed role and visual projection");
        }).thenExecute(()->{
            var n=captain[0];
            h.assertTrue(CharacterRegistry.currentId(p).equals(actor)&&n.getCharacterId().equals(uuid(prior,"person"))&&leadershipSummary(s,n).equals(prior.get("summary").getAsString()),"same person and exactly the persisted score");
            previousBread[0]=n.getPersonalInventory().countItem(Items.BREAD);
            near(p,n);
            command(h,p,"saga war muster retire "+old);
        }).thenWaitUntil(()->{
            h.assertTrue(ended(s,old),"original service releases");
            h.assertTrue(WarStaffRuntime.existing(s).orElseThrow().fieldCaptain(army,unit).isEmpty(),"original captain role revoked");
            h.assertTrue(((ChestBlockEntity)h.getLevel().getBlockEntity(at)).countItem(Items.IRON_SWORD)==1,"original borrowed sword returned");
            h.assertTrue(captain[0].getClothingOutfit().isEmpty(),"original body reverts to civilian clothing: "+captain[0].getClothingOutfit()+" ticks "+captain[0].tickCount);
        }).thenIdle(1220).thenExecute(()->{
                    var n=captain[0];near(p,n);
                    var transfer=com.jgeted.sagadyssey.npc.handover.NpcHandoverService.offer(p,n,net.minecraft.world.InteractionHand.MAIN_HAND,
                            false,com.jgeted.sagadyssey.npc.handover.HandoverIntent.GIFT,UUID.randomUUID());
                    h.assertTrue(transfer.accepted()&&p.getMainHandItem().isEmpty()&&n.getPersonalInventory().countItem(Items.STONE_SWORD)==1,
                            "archer's required personal melee backup arrives through actual gift");
                    h.assertTrue(!WorkCommitmentRuntime.blocksCivil(n),"old labor has actually been released for the original person");
                    next[0]=WarMusterGameTests.battleOfferFromAbove(h,p,n,"Leadership","Bows");
                })
                .thenWaitUntil(()->h.assertTrue(active(s,next[0])&&captain[0].getMainHandItem().is(Items.BOW)&&captain[0].getClothingOutfit().equals("foot_bow"),"original same person draws actual bow and displays new troop uniform"))
                .thenExecute(()->{
                    var n=captain[0];
                    h.assertTrue(n.getCharacterId().equals(uuid(prior,"person"))&&n.getUUID().equals(uuid(prior,"body"))&&n.getProfession()==NpcProfession.WORKER
                            &&n.getExperience()==prior.get("experience").getAsInt()&&leadershipSummary(s,n).equals(prior.get("summary").getAsString()),"civil identity, body and career survive re-enlistment");
                    h.assertTrue(n.getPersonalInventory().countItem(Items.BREAD)==previousBread[0]&&((ChestBlockEntity)h.getLevel().getBlockEntity(at)).countItem(Items.BOW)==0,"native loadout changes without giving food or replacing original stock");
                    prior.addProperty("nextService",next[0].toString());prior.addProperty("resumedProcess",ProcessHandle.current().pid());
                    leadershipProof(s,"resume",prior);
                });
        q.thenSucceed();
    }catch(Exception failure){throw new IllegalStateException(failure);}}
    private static UUID uuid(JsonObject row,String key){return UUID.fromString(row.get(key).getAsString());}
    private static String xyz(BlockPos p){return p.getX()+" "+p.getY()+" "+p.getZ();}
    private static void capture(GameTestHelper h,ServerPlayer p,BlockPos origin,List<NpcBase> people,List<UUID> services,UUID army,ChestBlockEntity source,ChestBlockEntity target,UUID task,String stage){try{
        var s=h.getLevel().getServer();var proof=new JsonObject();proof.addProperty("process",ProcessHandle.current().pid());proof.addProperty("account",p.getUUID().toString());proof.addProperty("name",p.getGameProfile().name());proof.addProperty("actor",CharacterRegistry.currentId(p).toString());proof.addProperty("army",army.toString());proof.addProperty("stage",stage);proof.addProperty("phase",phase());proof.addProperty("task",task.toString());proof.addProperty("headlessNoOpWire",true);proof.add("origin",new Gson().toJsonTree(List.of(origin.getX(),origin.getY(),origin.getZ())));proof.add("bodies",new Gson().toJsonTree(people.stream().map(NpcBase::getUUID).toList()));proof.add("people",new Gson().toJsonTree(people.stream().map(NpcBase::getCharacterId).toList()));proof.add("services",new Gson().toJsonTree(services));proof.add("professions",new Gson().toJsonTree(people.stream().map(n->n.getProfession().name()).toList()));proof.addProperty("playerEmeralds",p.getInventory().countItem(Items.EMERALD));proof.add("npcEmeralds",new Gson().toJsonTree(people.stream().map(n->n.getPersonalInventory().countItem(Items.EMERALD)).toList()));
        if(source!=null){proof.addProperty("sourceBread",source.countItem(Items.BREAD));proof.addProperty("sourceEmeralds",source.countItem(Items.EMERALD));proof.addProperty("sourceSwords",source.countItem(Items.IRON_SWORD));proof.addProperty("targetBread",target.countItem(Items.BREAD));proof.addProperty("carrierBread",people.get(1).getTransportInventory().countItem(Items.BREAD));}
        if(people.getFirst().getVehicle() instanceof Horse horse){proof.addProperty("horse",horse.getUUID().toString());proof.addProperty("hay",people.getFirst().getPersonalInventory().countItem(Items.HAY_BLOCK));}
        write(s.getWorldPath(LevelResource.ROOT).resolve("war-campaign-"+phase()+".json"),proof);if(Set.of("save","mount-save","offices-save").contains(phase()))write(s.getWorldPath(LevelResource.ROOT).resolve("war-campaign-save.json"),proof);
    }catch(Exception e){throw new IllegalStateException(e);}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopped(ServerStoppedEvent e)throws Exception {if(enabled()&&Set.of("save","mount-save","offices-save","leadership-save").contains(phase())){var root=e.getServer().getWorldPath(LevelResource.ROOT);var path=root.resolve(phase().equals("leadership-save")?"war-leadership-save.json":"war-campaign-save.json");if(!Files.exists(path))return;var proof=read(path);var data=NbtIo.readCompressed(root.resolve("level.dat"),NbtAccounter.create(64L*1024*1024)).getCompoundOrEmpty("Data");proof.addProperty("gameTime",data.getLongOr("Time",0));proof.addProperty("dayTime",data.getLongOr("DayTime",0));write(path,proof);}}
    private static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    private static void write(Path p,JsonObject o)throws Exception{Files.writeString(p,new GsonBuilder().setPrettyPrinting().create().toJson(o));}
}
