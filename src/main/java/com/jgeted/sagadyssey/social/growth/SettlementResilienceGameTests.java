package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.life.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey_resilience")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementResilienceGameTests {
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;var l=e.getServer().overworld();try{
            l.getStructureManager().getOrCreate(net.minecraft.resources.ResourceLocation.parse("sagadyssey_resilience:empty")).load(
                l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),net.minecraft.nbt.TagParser.parseTag("{size:[16,8,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    @BeforeBatch(batch="resilience") public static void setup(ServerLevel l){SettlementSafetyGameTests.setup(l);}
    @GameTestGenerator public static Collection<TestFunction> tests(){return List.of(
        test("blocked_pantry_uses_real_legal_bag",h->food(h,true)),
        test("no_alternative_waits_then_original_pantry_recovers",h->food(h,false)),
        test("registered_death_updates_food_demand",SettlementResilienceGameTests::demand));}
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body){return new TestFunction("resilience",name,"sagadyssey_resilience:empty",3200,0,true,body);}
    private static int count(Container c){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(Items.CARROT))n+=c.getItem(i).getCount();return n;}
    private static NaturalVillageBuilding fixture(GameTestHelper h,int offset){
        var b=NaturalVillageR1GameTests.fixture(h,offset,GrowthLayout.COORDINATED);var l=h.getLevel();
        l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,l.getServer());l.setDayTime(6000);
        l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,l.getServer());return b;
    }
    private static boolean ready(GameTestHelper h,NaturalVillageBuilding b){var d=SettlementCoordinatorData.existing(h.getLevel().getServer());return d!=null&&d.site(b.buildingKey())!=null;}
    private static void food(GameTestHelper h,boolean bag){
        var b=fixture(h,bag?0:256);var l=h.getLevel();long[] ready={-1},start={-1},beforeAte={-1};
        NpcBase[] actor={null};int[] pantry={0};var walls=new ArrayList<BlockPos>();UUID[] person={null},body={null};boolean[] restored={false};
        h.onEachTick(()->{
            if(!ready(h,b))return;long now=l.getGameTime();
            if(ready[0]<0){ready[0]=now;return;}if(now-ready[0]<20)return;
            var life=NpcLifeData.get(l.getServer());var box=NaturalPolityData.inventory(l.getServer(),b,1);
            if(start[0]<0){
                for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);NpcBrainRuntime.cancel(n,PlanCancellationReason.MANUAL,"declared initial controlled scene");n.setCommand(NpcCommand.STAY);}
                var n=NaturalVillageR1GameTests.actor(l,b,1);actor[0]=n;person[0]=n.getCharacterId();body[0]=n.getUUID();
                var center=NaturalVillageLayout.at(b,new BlockPos(6,1,9));
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=2;y++){
                    var at=center.offset(x,y,z);h.assertTrue(l.getBlockState(at).isAir(),"finite cage avoids original facilities");
                    if(y==2||Math.abs(x)==1||Math.abs(z)==1){l.setBlockAndUpdate(at,Blocks.GLASS.defaultBlockState());walls.add(at);}
                }
                n.getNavigation().stop();n.setPos(center.getX()+.5,center.getY(),center.getZ()+.5);
                h.assertTrue(count(n.getPersonalInventory())==0,"no pre-existing bag food");
                if(bag)n.getPersonalInventory().setItem(20,new ItemStack(Items.CARROT,4));
                life.setHunger(person[0],800,now);beforeAte[0]=life.needs(person[0]).lastAteAt();pantry[0]=count(box);start[0]=now;
                h.assertTrue(pantry[0]>0,"original pantry has real food");
                var policy=SupplyFlowData.get(l.getServer()).policies().stream().filter(p->p.id().equals("natural-diet:"+NaturalVillageLifeData.home(b))).findFirst().orElseThrow();
                h.assertTrue(policy.target()==64,"current product already targets 64, no new reserve feature");
                Sagadyssey.LOGGER.info("RESILIENCE_INITIAL bag={} quantity={} pantry={} target={} person={} body={} cage={} now={}",bag,bag?4:0,pantry[0],policy.target(),person[0],body[0],center,now);return;
            }
            var n=actor[0];long age=now-start[0];var needs=life.needs(person[0]);
            if(age%200==0)Sagadyssey.LOGGER.info("RESILIENCE_PROGRESS bag={} age={} hunger={} source={} personal={} pantry={} pos={} retry={} activity={}",bag,age,needs.hunger(),needs.lastFoodSource(),count(n.getPersonalInventory()),count(box),n.blockPosition(),LifeActivityData.get(l.getServer()).retryAt(n.getUUID()),n.getWorkActivityDetail());
            h.assertTrue(n.isAlive()&&n.getCharacterId().equals(person[0])&&n.getUUID().equals(body[0]),"original living identity retained");
            if(age==2){
                h.assertTrue(FieldworkRoutes.plan(n,NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY),false)==null,"actual closed local path");
                Sagadyssey.LOGGER.info("RESILIENCE_ROUTE_BLOCKED bag={} age={} personal={} pantry={} active={}",bag,age,count(n.getPersonalInventory()),count(box),NpcBrainRuntime.data(l.getServer()).active(n.getUUID()));
            }
            if(bag){
                if(needs.lastAteAt()>beforeAte[0]){
                    h.assertTrue(count(n.getPersonalInventory())==3&&count(box)==pantry[0]&&needs.hunger()<800,"one real personal meal; blocked pantry untouched");
                    Sagadyssey.LOGGER.info("RESILIENCE_BAG_ACCEPT age={} person={} before=4 after=3 pantry={} hunger={} source={}",age,person[0],count(box),needs.hunger(),needs.lastFoodSource());h.succeed();return;
                }
                h.assertTrue(age<400,"must use existing legal food within 400 ticks");
            }else{
                if(!restored[0]){
                    h.assertTrue(needs.lastAteAt()==beforeAte[0]&&count(box)==pantry[0],"no meal through blocked path and no virtual transfer");
                    if(age>=600){for(var p:walls)l.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());restored[0]=true;Sagadyssey.LOGGER.info("RESILIENCE_RESTORE age={} onlyInitialWallsRemoved=true",age);}
                }else if(needs.lastAteAt()>beforeAte[0]){
                    h.assertTrue(count(box)==pantry[0]-1&&count(n.getPersonalInventory())==0&&needs.hunger()<800,"original pantry meal through normal movement and consumption");
                    Sagadyssey.LOGGER.info("RESILIENCE_RETURN_ACCEPT age={} person={} originalPantry={} after={} source={}",age,person[0],pantry[0],count(box),needs.lastFoodSource());h.succeed();
                }
            }
        });
    }
    private static void demand(GameTestHelper h){
        var b=fixture(h,512);var l=h.getLevel();long[] killed={-1};UUID[] original={null};
        h.onEachTick(()->{
            if(!ready(h,b))return;var d=SettlementCoordinatorData.get(l.getServer());var row=d.site(b.buildingKey());
            if(killed[0]<0){if(row.getDouble("DailyMeals")!=4.5)return;
                var n=NaturalVillageR1GameTests.actor(l,b,2);original[0]=n.getCharacterId();n.kill();killed[0]=l.getGameTime();return;}
            if(l.getGameTime()-killed[0]<250)return;
            h.assertTrue(!CharacterRegistryData.get(l.getServer()).byId(original[0]).alive(),"real death history retained");
            h.assertTrue(row.getDouble("DailyMeals")==3.0,"two living members, not fixed three and not a new identity");
            Sagadyssey.LOGGER.info("RESILIENCE_DEMAND_ACCEPT dead={} dailyMealsBefore=4.5 after={} foodDays={}",original[0],row.getDouble("DailyMeals"),row.getDouble("FoodDays"));h.succeed();
        });
    }
}
