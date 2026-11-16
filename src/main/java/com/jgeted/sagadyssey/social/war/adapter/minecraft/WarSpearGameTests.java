package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.nbt.TagParser;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.identity.*;
import java.nio.file.Files;
import java.util.*;

@GameTestHolder("sagadyssey_war_spear") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarSpearGameTests {
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.spear.test");}
    private static String phase(){return System.getProperty("sagadyssey.war.spear.phase","jab");}
    private static final Set<UUID> damaged=new HashSet<>();private static final List<String> damageTypes=new ArrayList<>();
    @SubscribeEvent public static void damage(LivingDamageEvent.Post e){if(enabled()&&e.getSource().getDirectEntity() instanceof NpcBase n&&n.getMainHandItem().is(net.minecraft.tags.ItemTags.SPEARS)&&e.getNewDamage()>0){damaged.add(n.getUUID());damageTypes.add(e.getSource().typeHolder().unwrapKey().orElseThrow().identifier().toString());}}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent e)throws Exception{
        if(!enabled()||!(e.getServer() instanceof GameTestServer))return;var l=e.getServer().overworld();l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME,false,e.getServer());l.getGameRules().set(GameRules.SPAWN_MOBS,false,e.getServer());
        l.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_spear:empty")).load(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_spear",phase(),"sagadyssey_war_spear:empty",9000,0,true,WarSpearGameTests::run)):List.of();}
    private static void run(GameTestHelper h){try{
        var s=h.getLevel().getServer();var l=h.getLevel();var origin=h.absolutePos(BlockPos.ZERO).offset(3072,-3,0);
        for(int x=0;x<64;x++)for(int z=0;z<56;z++){var p=origin.offset(x,0,z);l.getChunkAt(p);l.setChunkForced(p.getX()>>4,p.getZ()>>4,true);l.setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());for(int y=1;y<6;y++)l.setBlockAndUpdate(p.above(y),Blocks.AIR.defaultBlockState());}
        var lord=WarMusterGameTests.battlePlayer(h,"SpearLord",BlockPos.ZERO);lord.getInventory().setItem(3,new ItemStack(Items.EMERALD,64));
        var items=phase().equals("jab")?List.of(Items.WOODEN_SPEAR,Items.STONE_SPEAR,Items.COPPER_SPEAR,Items.IRON_SPEAR,Items.GOLDEN_SPEAR,Items.DIAMOND_SPEAR,Items.NETHERITE_SPEAR):List.of(Items.IRON_SPEAR);
        var people=new ArrayList<NpcBase>();var enemies=new ArrayList<NpcBase>();var services=new ArrayList<UUID>();var units=new ArrayList<UUID>();
        command(h,lord,"saga war muster create Spears");var army=WarMusterEntry.armyId(CharacterRegistry.currentId(lord),"Spears");
        for(int i=0;i<items.size();i++){
            var n=npc(h,origin.offset(5,1,4+i*6));n.setProfession(NpcProfession.WORKER);n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(items.get(i)));n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,16));n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));NpcLifeData.get(s).onRecruited(n,CharacterRegistry.currentId(lord),l.getGameTime());NpcLifeData.get(s).setHunger(n.getCharacterId(),100,l.getGameTime());people.add(n);
            var enemy=npc(h,origin.offset(phase().equals("charge")?25:7,1,4+i*6));enemy.setNoAi(true);enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);enemy.setHealth(1000);enemy.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);enemies.add(enemy);units.add(WarMusterEntry.unitId(army,"Grade"+i));
        }
        var blockers=new ArrayList<BlockPos>();if(phase().equals("guards"))for(var d:Direction.Plane.HORIZONTAL)for(int y=0;y<3;y++){var at=enemies.getFirst().blockPosition().relative(d).above(y);blockers.add(at);l.setBlockAndUpdate(at,Blocks.STONE.defaultBlockState());}
        var q=h.startSequence().thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().owns(CharacterRegistry.currentId(lord),army),"original army durable"));
        for(int i=0;i<items.size();i++){int index=i;q.thenExecute(()->command(h,lord,"saga war muster unit Spears Grade"+index+" pike"))
            .thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().unit(units.get(index)).filter(u->u.recorded()&&WarMusterRuntime.existing(s).orElseThrow().book().durable(u)).isPresent(),"original spear unit durable"))
            .thenExecute(()->services.add(WarMusterGameTests.battleOffer(h,lord,people.get(index),"Spears","Grade"+index)))
            .thenWaitUntil(()->h.assertTrue(WarMusterRuntime.existing(s).orElseThrow().book().offer(services.get(index)).filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE&&WarMusterRuntime.existing(s).orElseThrow().book().durable(o)).isPresent(),"same original spear body active"));}
        q.thenExecute(()->{
            CombatEncounterService.start(CombatEncounterData.get(s),UUID.randomUUID(),null,l.dimension().identifier().toString(),enemies.getFirst().blockPosition(),96,List.of(new CombatSide(UUID.randomUUID(),"original spear soldiers",null,new HashSet<>(people.stream().map(NpcBase::getCharacterId).toList()),EngagementRule.LETHAL_FORCE),new CombatSide(UUID.randomUUID(),"explicit hostile fixture",null,new HashSet<>(enemies.stream().map(NpcBase::getCharacterId).toList()),EngagementRule.LETHAL_FORCE)),l.getGameTime());
            for(int i=0;i<people.size();i++){var core=WarServerEvents.existing(s).orElseThrow();var unit=core.session().orElseThrow().unit(units.get(i)).orElseThrow();var target=phase().equals("charge")?enemies.get(i).blockPosition():people.get(i).blockPosition();var order=new WarCommand.IssueOrder(UUID.randomUUID(),core.session().orElseThrow().generation(),CharacterRegistry.currentId(lord),army,units.get(i),unit.revision(),new WarIntent(phase().equals("charge")?InitialWarDefinitions.CHARGE:InitialWarDefinitions.HOLD,1,new WarIntent.Position(WarKey.parse(l.dimension().identifier().toString()),target.getX(),target.getY(),target.getZ())));h.assertTrue(core.field().orElseThrow().dispatch(order).outcome()==com.jgeted.sagadyssey.social.war.application.WarDispatchService.Outcome.ACCEPTED,"ordinary received spear order");}
        });
        if(phase().equals("guards")){var bystander=new NpcBase[1];q.thenIdle(60).thenExecute(()->{h.assertTrue(damaged.isEmpty()&&enemies.getFirst().getHealth()==1000,"real wall blocks spear contact");for(var at:blockers)l.setBlockAndUpdate(at,Blocks.AIR.defaultBlockState());var n=npc(h,origin.offset(6,1,4));n.setNoAi(true);n.setHealth(n.getMaxHealth());bystander[0]=n;})
            .thenIdle(60).thenExecute(()->{h.assertTrue(damaged.isEmpty()&&enemies.getFirst().getHealth()==1000&&bystander[0].getHealth()==bystander[0].getMaxHealth(),"native piercing ray rejects neutral body before damage, knockback or dismount");bystander[0].teleportTo(origin.getX()+20.5,origin.getY()+1,origin.getZ()+20.5);});}
        q.thenWaitUntil(()->h.assertTrue(people.stream().allMatch(n->damaged.contains(n.getUUID())),"every actual spear produces native damage: "+WarServerEvents.existing(s).orElseThrow().field().orElseThrow().reports(CharacterRegistry.currentId(lord),army)))
            .thenExecute(()->{h.assertTrue(damageTypes.stream().allMatch("minecraft:spear"::equals),"native spear damage type, no sword or injected damage");var p=new JsonObject();p.addProperty("process",ProcessHandle.current().pid());p.addProperty("phase",phase());p.add("bodies",new Gson().toJsonTree(people.stream().map(NpcBase::getUUID).toList()));p.add("services",new Gson().toJsonTree(services));p.add("weapons",new Gson().toJsonTree(items.stream().map(i->net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).toString()).toList()));p.add("damageTypes",new Gson().toJsonTree(damageTypes));try{Files.writeString(s.getWorldPath(LevelResource.ROOT).resolve("war-spear-"+phase()+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(p));}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}}).thenSucceed();
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static NpcBase npc(GameTestHelper h,BlockPos pos){var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);n.teleportTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5);n.setPersistenceRequired();n.setCommand(NpcCommand.STAY);for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);var registry=CharacterRegistryData.get(h.getLevel().getServer());registry.ensureNpcCharacter(n.getCharacterId(),"original spear fixture",NpcSocialClass.COMMONER,NpcOffice.NONE,"neutral",h.getLevel().getGameTime());registry.bindNpcBody(n.getCharacterId(),h.getLevel().dimension().identifier().toString(),n.getUUID(),h.getLevel().getGameTime());return n;}
    private static void command(GameTestHelper h,ServerPlayer p,String text){h.assertTrue(WarMusterGameTests.command(p,text)==1,"ordinary command "+text+"; "+WarMusterGameTests.feedback(p));}
}
