package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.google.gson.Gson;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Observation-only calibration after the declared, finite initial inventory split. */
@GameTestHolder("sagadyssey_natural_life") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NaturalLifeGameTests {
    private static final Identifier EMPTY=Identifier.parse("sagadyssey_natural_life:empty");
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;
        var l=e.getServer().overworld();
        try {l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),
                TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));}
        catch(Exception x){throw new IllegalStateException(x);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){
        return switch(System.getProperty("sagadyssey.society.phase","")){
            case "natural-d0","natural-cold"->List.of(new TestFunction("natural_life","cold_start_calibration",EMPTY.toString(),48600,0,true,NaturalLifeGameTests::calibrate));
            case "natural-members"->NaturalFarmMemberGameTests.tests();
            case "natural-seeds"->NaturalFarmSeedGameTests.tests();
            case "natural-food-protection"->NaturalFarmFoodProtectionGameTests.tests();
            case "natural-food-failures"->NaturalFarmFoodFailureGameTests.tests();
            case "natural-food-priority"->NaturalFarmFoodFailureGameTests.tests(true);
            case "natural-night"->NaturalFarmEmergencyGameTests.tests(false);
            case "natural-exhausted"->NaturalFarmEmergencyGameTests.tests(true);
            case "natural-carry-save"->NaturalFarmRestartGameTests.tests(false);
            case "natural-carry-resume"->NaturalFarmRestartGameTests.tests(true);
            default->List.of();
        };
    }
    private static void calibrate(GameTestHelper h){
        boolean revised=System.getProperty("sagadyssey.society.phase","").equals("natural-cold");
        var b=NaturalVillageR1GameTests.fixture(h,0,revised?NaturalVillageLayout.FARM_V3:NaturalVillageLayout.FARM_V2);
        var l=h.getLevel();var state=new Observation(h,b);
        h.onEachTick(()->{
            var v=NaturalVillageLifeData.get(l.getServer()).village(b.buildingKey());
            if(state.began<0){
                if(v==null||!v.getBooleanOr("Ready", false))return;
                var farmer=NaturalVillageR1GameTests.actor(l,b,0);var pantry=state.store(NaturalVillageLayout.PANTRY);
                h.assertTrue(count(farmer.getPersonalInventory())==(revised?12:16)&&count(pantry)==(revised?4:0),"unchanged finite initialization before calibration split");
                h.assertTrue(FieldworkData.get(l.getServer()).receiptsFor(farmer.getUUID()).isEmpty(),"no harvest before declared initial split");
                h.assertTrue(NaturalVillageLayout.crops().stream().allMatch(p->{var s=l.getBlockState(NaturalVillageLayout.at(b,p));return s.is(Blocks.CARROTS)&&s.getValue(CropBlock.AGE)<7;}),"no mature field substituted for cold start");
                if(!revised)for(int slot=0;slot<farmer.getPersonalInventory().getContainerSize();slot++){
                    var stack=farmer.getPersonalInventory().getItem(slot);
                    if(stack.is(Items.CARROT)&&stack.getCount()>=4){pantry.setItem(0,stack.split(4));farmer.getPersonalInventory().setChanged();pantry.setChanged();break;}
                }
                h.assertTrue(count(farmer.getPersonalInventory())==12&&count(pantry)==4,"12 protected-intent seeds plus four pantry food, no added resources");
                state.began=l.getGameTime();
                state.event("PREPARED",Map.of("village",v.toString(),"initialCarrots",16,"farmerCarrots",12,"pantryCarrots",4,
                        "oldSeedAndConsumptionRules",!revised,"fixtureMobSpawning",false,"randomTickSpeed",3,"daylightCycle",true));
                state.snapshot();
            }
            state.tick();
        });
    }
    static int count(Container c){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(Items.CARROT))n+=c.getItem(i).getCount();return n;}
    static final class Observation {
        final GameTestHelper h;final ServerLevel l;final NaturalVillageBuilding b;final Path log;
        long began=-1,firstDelivery=-1;int meals;long netCrop;
        final Set<String> receipts=new HashSet<>(),mature=new HashSet<>(),routeStates=new HashSet<>();
        final Set<Integer> postDeliveryMeals=new HashSet<>();
        final long[] lastMeal={-1,-1,-1},severeSince={-1,-1,-1};
        boolean harvest,plant,raw,carrying,delivered;int lowestPantry=4;
        Observation(GameTestHelper helper,NaturalVillageBuilding village){h=helper;l=h.getLevel();b=village;log=l.getServer().getWorldPath(LevelResource.ROOT).resolve("natural-life-d0.jsonl");}
        Container store(BlockPos p){return (Container)l.getBlockEntity(NaturalVillageLayout.at(b,p));}
        void event(String kind,Object detail){
            var row=new LinkedHashMap<String,Object>();row.put("tick",l.getGameTime());row.put("elapsed",began<0?0:l.getGameTime()-began);row.put("event",kind);row.put("detail",detail);
            try{Files.writeString(log,new Gson().toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
            catch(Exception e){throw new IllegalStateException("Cannot retain calibration evidence",e);}
        }
        void require(boolean value,String why){if(!value){snapshot();event("FAIL",why);save();}h.assertTrue(value,why);}
        void tick(){
            long now=l.getGameTime(),elapsed=now-began;
            for(int i=0;i<3;i++){
                var n=NaturalVillageR1GameTests.actor(l,b,i);
                require(n!=null&&n.isAlive()&&n.getCharacterId().equals(NaturalVillageLifeData.person(b,i)),"original resident alive and unique, index="+i);
                var need=NpcLifeData.get(l.getServer()).needs(n.getCharacterId());
                if(need==null)continue;
                if(need.lastAteAt()!=lastMeal[i]&&need.lastAteAt()>=0){
                    lastMeal[i]=need.lastAteAt();meals++;if(firstDelivery>=0&&need.lastAteAt()>=firstDelivery)postDeliveryMeals.add(i);
                    event("ATE",Map.of("resident",i,"body",n.getUUID(),"person",n.getCharacterId(),"at",need.lastAteAt(),"source",need.lastFoodSource(),"hunger",need.hunger()));
                }
                if(need.hunger()>=800){if(severeSince[i]<0)severeSince[i]=now;require(now-severeSince[i]<2400,"unresolved severe hunger for 2400 ticks, resident="+i);}else severeSince[i]=-1;
            }
            // The five-block route can pass through CARRYING between 20-tick snapshots.
            // Observe its real cargo and stage every tick; never infer a missed state from DONE.
            for(var route:SupplyFlowData.get(l.getServer()).routes())if(route.principal().equals(SubjectRef.household(NaturalVillageLifeData.home(b)))){
                if(routeStates.add(route.id()+"/"+route.stage()))event("ROUTE",route.toString());
                if(route.stage()==SupplyFlowData.Stage.CARRYING){
                    var carrier=l.getEntity(route.carrierEntity());
                    if(carrier instanceof NpcBase n&&count(n.getTransportInventory())>0){
                        if(!carrying)event("CARRYING_PHYSICAL",Map.of("route",route.id(),"body",n.getUUID(),"cargo",count(n.getTransportInventory()),"position",n.position().toString()));
                        carrying=true;
                    }
                }
                if(route.stage()==SupplyFlowData.Stage.DONE&&!delivered){delivered=true;firstDelivery=now;event("PANTRY_DELIVERED",Map.of("route",route.id(),"quantity",route.quantity()));}
            }
            if(elapsed%20!=0)return;
            var economy=NaturalVillageEconomyData.existing(l.getServer());var row=economy==null?null:economy.row(b.buildingKey());
            require(row==null||row.getStringOr("Phase", "").equals("NEAR"),"calibration remains physical NEAR");
            for(var p:NaturalVillageLayout.crops()){
                var at=NaturalVillageLayout.at(b,p);var s=l.getBlockState(at);
                if(s.is(Blocks.CARROTS)&&s.getValue(CropBlock.AGE)==7&&mature.add(at.toShortString()))event("MATURE",Map.of("plot",at.toShortString()));
            }
            for(var receipt:FieldworkData.get(l.getServer()).receiptsFor(NaturalVillageLifeData.body(b,0))){
                if(!receipts.add(receipt.action().key()))continue;
                long delta=receipt.inventoryDeltas().stream().filter(d->d.fingerprint().equals("minecraft:carrot#0")).mapToLong(FieldworkActionReceipt.ItemDelta::quantity).sum();netCrop+=delta;
                var op=receipt.action().operation();harvest|=op==FieldworkAction.Operation.HARVEST;plant|=op==FieldworkAction.Operation.PLANT;
                event(op.toString(),Map.of("action",receipt.action().key(),"plot",receipt.action().target().toShortString(),"at",receipt.committedAt(),"delta",delta));
            }
            if(count(store(NaturalVillageLayout.RAW))>0&&!raw){raw=true;event("RAW_AVAILABLE",Map.of("carrots",count(store(NaturalVillageLayout.RAW))));}
            int stock=stock();lowestPantry=Math.min(lowestPantry,count(store(NaturalVillageLayout.PANTRY)));
            require(16+netCrop==stock+meals,"carrot conservation: 16 + netCrop="+netCrop+" stock="+stock+" meals="+meals+" elapsed="+elapsed);
            if(elapsed%200==0)snapshot();
            if(harvest&&plant&&raw&&carrying&&delivered&&postDeliveryMeals.size()==3){
                event("CALIBRATION_COMPLETE",Map.of("elapsed",elapsed,"netCrop",netCrop,"meals",meals,"stock",stock,"minimumPantry",lowestPantry));save();
                Sagadyssey.LOGGER.info("NATURAL_D0_COMPLETE elapsed={} netCrop={} meals={} stock={} minPantry={}",elapsed,netCrop,meals,stock,lowestPantry);h.succeed();
            } else if(elapsed>=48000)require(false,"cold start deadline: matured="+mature.size()+" harvest="+harvest+" plant="+plant+" raw="+raw+" carried="+carrying+" delivered="+delivered+" fedAfterDelivery="+postDeliveryMeals);
        }
        int stock(){int n=0;for(var p:NaturalVillageLayout.stores(b)){var c=l.getBlockEntity(NaturalVillageLayout.at(b,p));if(c instanceof Container box)n+=count(box);}for(int i=0;i<3;i++){var a=NaturalVillageR1GameTests.actor(l,b,i);if(a!=null)n+=count(a.getPersonalInventory())+count(a.getTransportInventory());}return n;}
        void snapshot(){
            var actors=new ArrayList<Map<String,Object>>();
            for(int i=0;i<3;i++){
                var n=NaturalVillageR1GameTests.actor(l,b,i);if(n==null){actors.add(Map.of("resident",i,"missing",true));continue;}
                var a=new LinkedHashMap<String,Object>();a.put("resident",i);a.put("body",n.getUUID());a.put("person",n.getCharacterId());a.put("position",n.position().toString());a.put("health",n.getHealth());
                a.put("needs",String.valueOf(NpcLifeData.get(l.getServer()).needs(n.getCharacterId())));a.put("bag",count(n.getPersonalInventory()));a.put("cargo",count(n.getTransportInventory()));a.put("sleeping",n.isSleeping());a.put("work",n.getWorkActivityDetail());actors.add(a);
            }
            var stores=new LinkedHashMap<String,Integer>();for(var p:NaturalVillageLayout.stores(b)){var c=l.getBlockEntity(NaturalVillageLayout.at(b,p));if(c instanceof Container box)stores.put(p.toShortString(),count(box));}
            var economy=NaturalVillageEconomyData.existing(l.getServer());var row=economy==null?null:economy.row(b.buildingKey());
            event("SNAPSHOT",Map.of("actors",actors,"stores",stores,"netCrop",netCrop,"meals",meals,"phase",row==null?"not-enrolled":row.getStringOr("Phase", ""),"dayTime",l.getDayTime()));
        }
        void save(){try{l.getServer().saveEverything(true,true,true);}catch(Exception e){throw new IllegalStateException(e);}}
    }
}
