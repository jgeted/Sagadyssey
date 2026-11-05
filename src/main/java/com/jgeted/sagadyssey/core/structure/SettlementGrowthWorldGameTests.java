package com.jgeted.sagadyssey.core.structure;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.social.growth.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Read-only business observer, absent from the production jar. Only loading/tick pacing are configured. */
@EventBusSubscriber(modid="sagadyssey")
public final class SettlementGrowthWorldGameTests {
    private static final Gson JSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static Run run;
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.growth.observe");}
    @SubscribeEvent public static void before(ServerAboutToStartEvent e){if(enabled())try{run=new Run(e.getServer());run.prepare();}catch(Exception ex){throw new IllegalStateException(ex);}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent e){if(run!=null&&run.server==e.getServer())try{run.start();}catch(Exception ex){run.fail(ex);}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){if(run!=null&&run.server==e.getServer()&&!run.stopping)try{run.tick();}catch(Exception ex){run.fail(ex);}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){if(run!=null&&run.server==e.getServer())try{run.stopped();}catch(Exception ex){throw new IllegalStateException(ex);}}
    static class State {
        long ready=-1,last=-1;long[] meals={0,0,0},lastMeal={-1,-1,-1};
        Set<String> actions=new HashSet<>(),routeStages=new HashSet<>(),carrying=new HashSet<>();
        Map<String,Long> opening=new TreeMap<>(),net=new TreeMap<>();
        Set<String> newHarvested=new HashSet<>(),tilled=new HashSet<>();
        long mined,slept=-1,allFeatures=-1,firstMeal=-1;String growth="";
        Map<String,Long> firstNeeds=new TreeMap<>();
        long formalStart=-1;long[] formalMeals={0,0,0},severeSince={-1,-1,-1},maxSevereTicks={0,0,0};int[] maxHunger={0,0,0};
    }
    static class Run {
        final MinecraftServer server;final int episode=Integer.getInteger("sagadyssey.growth.episode",0);
        final long seed=Long.getLong("sagadyssey.growth.seed",7019L),duration=Long.getLong("sagadyssey.growth.duration",240000L);
        final long restart=Long.getLong("sagadyssey.growth.restart",-1L);
        final boolean formal=Boolean.getBoolean("sagadyssey.growth.formal");
        final BlockPos origin=seed==7019?new BlockPos(-1088,86,-752):new BlockPos(16,69,800);
        State state=new State();NaturalVillageBuilding village;Path root,events;boolean stopping;String reason="";long startedAt;boolean restoredEntities;
        Run(MinecraftServer s){server=s;}
        void require(boolean b,String message){if(!b)throw new IllegalStateException(message);}
        void prepare()throws Exception{
            require(seed==7019||seed==7021,"Only documented native locations are admitted");
            require(server.getWorldData().worldGenOptions().seed()==seed&&!server.getWorldData().isFlatWorld(),"Native seed/generator mismatch");
            root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent();
            if(episode>0){var old=JsonParser.parseString(Files.readString(root.resolve("episode-"+(episode-1)+"-stopped.json"))).getAsJsonObject();
                require(old.get("normalStop").getAsBoolean(),"Previous process did not finish saving");state=JSON.fromJson(old.get("state"),State.class);
                require(server.getWorldData().overworldData().getGameTime()==state.last||state.last==-1&&state.ready==-1&&!server.getWorldData().overworldData().isInitialized()&&server.getWorldData().overworldData().getGameTime()==0,"Resume clock mismatch");
            }else require(!server.getWorldData().overworldData().isInitialized()&&server.getWorldData().overworldData().getGameTime()==0,"Never overwrite or reset an existing world");
            events=root.resolve("episode-"+episode+"-events.jsonl");Files.createFile(events);
            if(formal&&state.formalStart<0&&state.ready>=0){state.formalStart=server.getWorldData().overworldData().getGameTime();state.formalMeals=state.meals.clone();}
            emit("ADMISSION",Map.of("seed",seed,"episode",episode,"duration",duration,"restart",restart,"world",server.getWorldPath(LevelResource.ROOT).toString(),"observerOnly",true));
        }
        static final TicketType<ChunkPos> TICKET=TicketType.create("growth-observer",Comparator.comparingLong(ChunkPos::toLong));
        void start()throws Exception{
            var l=server.overworld();startedAt=l.getGameTime();l.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);
            server.tickRateManager().setTickRate(formal?1000:400);
            for(int x=(origin.getX()>>4)-3;x<=(origin.getX()>>4)+3;x++)for(int z=(origin.getZ()>>4)-3;z<=(origin.getZ()>>4)+3;z++){
                var cp=new ChunkPos(x,z);l.setChunkForced(x,z,true);l.getChunk(x,z);l.getChunkSource().addRegionTicket(TICKET,cp,2,cp,true);
            }
            emit("LOADING",Map.of("radiusChunks",3,"nativeGeneration",true,"pacingTPS",formal?1000:400,"randomTickSpeed",l.getGameRules().getInt(GameRules.RULE_RANDOMTICKING),"players",0,"formal",formal,"formalStart",state.formalStart,"processors",Runtime.getRuntime().availableProcessors(),"maxHeap",Runtime.getRuntime().maxMemory()));
        }
        void tick()throws Exception{
            var l=server.overworld();long now=l.getGameTime();require(state.last<0||now==state.last+1,"No skipped, repeated or rewritten simulation ticks");state.last=now;
            if(Files.exists(root.resolve("stop-observation-episode-"+episode+".request"))){if(village!=null){observe();audit();}stop("DIAGNOSTIC_STOP_REQUESTED");return;}
            var data=NaturalVillageLifeData.existing(server);
            if(village==null&&data!=null)for(var row:data.villages()){
                var b=NaturalVillageLifeData.decode(row);if(b.buildingOrigin().equals(origin)&&row.getBoolean("Ready")){require(GrowthLayout.supports(b),"Wrong natural template");village=b;break;}
            }
            if(village==null){require(now<2400,"Natural initialization not reached");return;}
            if(episode>0&&!restoredEntities){
                var missing=new ArrayList<UUID>();for(int i=0;i<3;i++)if(!(l.getEntity(NaturalVillageLifeData.body(village,i)) instanceof NpcBase))missing.add(NaturalVillageLifeData.body(village,i));
                for(var tag:growth().getList("Animals",Tag.TAG_COMPOUND)){var animal=(CompoundTag)tag;if(!animal.contains("Died")&&l.getEntity(animal.getUUID("Id"))==null)missing.add(animal.getUUID("Id"));}
                if(!missing.isEmpty()){
                    if(now%20==0)emit("WAITING_FOR_SAVED_ENTITY_CHUNKS",Map.of("missing",missing,"elapsedSinceStart",now-startedAt));
                    require(now-startedAt<=200,"Saved entities not loaded within 200 ticks: "+missing);return;
                }
                restoredEntities=true;emit("SAVED_ENTITIES_LOADED",Map.of("elapsedSinceStart",now-startedAt,"identityChecksFollow",true));
            }
            if(state.ready<0){state.ready=now;state.opening.putAll(physical());require(state.opening.getOrDefault("minecraft:carrot",0L)==16,"Finite original carrot inventory");emit("NATURAL_READY",snapshot());}
            if(formal&&state.formalStart<0){state.formalStart=now;state.formalMeals=state.meals.clone();}
            if(now%(formal?200:20)==0){observe();audit();}
            if(now%(formal?24000:2400)==0)emit("SNAPSHOT",snapshot());
            if(now%2400==0)Files.writeString(root.resolve("progress.json"),JSON.toJson(Map.of("gameTime",now,"elapsed",now-state.ready,"formalElapsed",state.formalStart<0?-1:now-state.formalStart,"meals",state.meals,"maxHunger",state.maxHunger,"maxSevereTicks",state.maxSevereTicks,"allFeatures",state.allFeatures,"growth",growth().toString(),"reason",reason)));
            long elapsed=now-(formal?state.formalStart:state.ready);
            if(restart>0&&elapsed>=restart){stop("PLANNED_RESTART");return;}
            if(elapsed>=duration){observe();audit();stop(state.allFeatures>=0?"OBSERVATION_COMPLETE":"DEVELOPMENT_INCOMPLETE_AT_FIXED_END");}
        }
        CompoundTag growth(){var d=SettlementGrowthData.existing(server);var r=d==null||village==null?null:d.site(village.buildingKey());return r==null?new CompoundTag():r;}
        void observe()throws Exception{
            var l=server.overworld();long now=l.getGameTime();var life=NpcLifeData.get(server);
            for(int i=0;i<3;i++){
                var e=l.getEntity(NaturalVillageLifeData.body(village,i));require(e instanceof NpcBase,"Original resident entity missing");var n=(NpcBase)e;
                require(n.isAlive()&&n.getCharacterId().equals(NaturalVillageLifeData.person(village,i)),"Original person died/was replaced");
                var needs=life.needs(n.getCharacterId());require(needs!=null,"Missing life record");
                if(formal){
                    state.maxHunger[i]=Math.max(state.maxHunger[i],needs.hunger());
                    if(needs.hunger()>=900){if(state.severeSince[i]<0)state.severeSince[i]=now;state.maxSevereTicks[i]=Math.max(state.maxSevereTicks[i],now-state.severeSince[i]);}
                    else state.severeSince[i]=-1;
                    require(state.maxSevereTicks[i]<24000,"Sustained severe hunger for a full day: original resident "+i);
                }
                if(needs.lastAteAt()>=0&&needs.lastAteAt()!=state.lastMeal[i]){state.lastMeal[i]=needs.lastAteAt();state.meals[i]++;if(state.firstMeal<0)state.firstMeal=now;emit("MEAL",Map.of("person",n.getCharacterId(),"at",needs.lastAteAt(),"hunger",needs.hunger(),"source",needs.lastFoodSource()));}
                if(i==2&&n.isSleeping()&&n.getSleepingPos().filter(NaturalVillageLayout.at(village,GrowthLayout.BED_HEAD)::equals).isPresent()&&state.slept<0){state.slept=now;emit("ORIGINAL_RESIDENT_SLEPT_IN_NEW_HOUSE",Map.of("person",n.getCharacterId(),"at",now));}
            }
            var work=l.getDataStorage().get(FieldworkData.FACTORY,FieldworkData.DATA_NAME);
            if(work!=null)for(int i=0;i<3;i++)for(var receipt:work.receiptsFor(NaturalVillageLifeData.body(village,i))){
                if(!state.actions.add(receipt.action().key()))continue;
                for(var delta:receipt.inventoryDeltas())state.net.merge(delta.fingerprint().split("#",2)[0],(long)delta.quantity(),Long::sum);
                String operation=receipt.action().operation().name();BlockPos p=receipt.action().target();
                if(operation.equals("MINE"))state.mined++;
                if(GrowthLayout.extension().stream().map(cell->NaturalVillageLayout.at(village,cell)).anyMatch(cell->cell.getX()==p.getX()&&cell.getZ()==p.getZ())){
                    if(operation.equals("TILL"))state.tilled.add(p.toShortString());if(operation.equals("HARVEST")&&receipt.action().expectedState().contains("minecraft:carrots"))state.newHarvested.add(p.toShortString());
                }
                emit("ACTUAL_WORK",Map.of("resident",i,"receipt",receipt.toString()));
            }
            var g=growth();String status=g.getString("HouseStage")+":"+g.getInt("Placed")+":"+g.getInt("ConsumedFeed")+":"+g.getList("Animals",Tag.TAG_COMPOUND).size()+":"+g.getBoolean("FieldApproved");
            require(!g.getBoolean("Review"),"Growth requires review: "+g.getString("Reason"));
            if(!status.equals(state.growth)){state.growth=status;emit("DEVELOPMENT",nbt(g));}
            for(var tag:g.getList("Animals",Tag.TAG_COMPOUND)){
                var animal=(CompoundTag)tag;require(!animal.contains("Died"),"Registered herd death: "+animal.getUUID("Id"));
                require(l.getEntity(animal.getUUID("Id")) instanceof net.minecraft.world.entity.animal.Pig pig&&pig.isAlive(),"Registered herd entity absent in fully loaded sample");
            }
            for(var route:SupplyFlowData.get(server).routes()){
                if(!route.principal().id().equals(NaturalVillageLifeData.home(village)))continue;
                if(state.routeStages.add(route.id()+"/"+route.stage()))emit("ROUTE",Map.of("route",route.toString(),"order",String.valueOf(LogisticsData.get(server).order(route.id()))));
                if(route.stage()==SupplyFlowData.Stage.CARRYING)state.carrying.add(route.id().toString());
            }
            if(g.getString("HouseStage").equals("DONE")&&state.newHarvested.size()==12&&state.mined>=55&&g.getInt("ConsumedFeed")>=4&&g.getList("Animals",Tag.TAG_COMPOUND).size()==4&&state.slept>=0&&Arrays.stream(state.meals).allMatch(v->v>0)&&state.allFeatures<0){state.allFeatures=now;emit("FOUR_FEATURES_OBSERVED",snapshot());}
        }
        void add(Map<String,Long> counts,ItemStack stack){if(!stack.isEmpty())counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),(long)stack.getCount(),Long::sum);}
        Map<String,Long> physical(){
            var out=new TreeMap<String,Long>();var l=server.overworld();
            for(var local:NaturalVillageLayout.stores(village))if(l.getBlockEntity(NaturalVillageLayout.at(village,local)) instanceof Container c)for(int i=0;i<c.getContainerSize();i++)add(out,c.getItem(i));
            for(int i=0;i<3;i++)if(l.getEntity(NaturalVillageLifeData.body(village,i)) instanceof NpcBase n){
                for(int slot=0;slot<n.getPersonalInventory().getContainerSize();slot++)add(out,n.getPersonalInventory().getItem(slot));
                for(int slot=0;slot<n.getTransportInventory().getContainerSize();slot++)add(out,n.getTransportInventory().getItem(slot));
                add(out,n.getMainHandItem());add(out,n.getOffhandItem());for(var stack:n.getArmorSlots())add(out,stack);
            }
            for(var drop:l.getEntitiesOfClass(ItemEntity.class,new AABB(origin).inflate(40))){
                String key=BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString();
                if(state.opening.containsKey(key)||state.net.containsKey(key)||Set.of("minecraft:carrot","minecraft:cobblestone","minecraft:oak_planks","minecraft:oak_door","minecraft:white_bed").contains(key))add(out,drop.getItem());
            }return out;
        }
        Map<String,Long> expected(){
            var out=new TreeMap<>(state.opening);state.net.forEach((k,v)->out.merge(k,v,Long::sum));
            var g=growth();out.merge("minecraft:carrot",-Arrays.stream(state.meals).sum()-(long)g.getInt("ConsumedFeed"),Long::sum);
            if(g.getInt("Labor")>0)for(var batch:ProductionBatchData.get(server).batches())if(batch.containerId().equals(NaturalVillageEconomyData.storeId(village,3))&&batch.status()==ProductionBatchStatus.COMPLETED){
                for(var material:batch.inputs())out.merge(material.fingerprint().itemId(),-(long)material.quantity(),Long::sum);out.merge(batch.output().fingerprint().itemId(),(long)batch.output().quantity(),Long::sum);
            }
            var pieces=GrowthLayout.house(village,g.getString("WallMaterial").equals("WOOD"));for(int i=0;i<g.getInt("Placed");i++)out.merge(BuiltInRegistries.ITEM.getKey(pieces.get(i).material()).toString(),-1L,Long::sum);
            return out;
        }
        void audit()throws Exception{
            var actual=physical();var expected=expected();var keys=new TreeSet<>(expected.keySet());keys.addAll(actual.keySet());
            for(var key:keys)require(Objects.equals(actual.getOrDefault(key,0L),expected.getOrDefault(key,0L)),"Item balance mismatch "+key+" expected="+expected.getOrDefault(key,0L)+" actual="+actual.getOrDefault(key,0L));
        }
        Object snapshot(){
            var out=new LinkedHashMap<String,Object>();out.put("time",server.overworld().getGameTime());out.put("elapsed",state.ready<0?-1:state.last-state.ready);
            out.put("summary",Map.of("meals",state.meals,"actions",state.actions.size(),"mined",state.mined,"tilled",state.tilled.size(),"newHarvested",state.newHarvested.size(),"slept",state.slept,"allFeatures",state.allFeatures));
            out.put("formalObservation",Map.of("enabled",formal,"start",state.formalStart,"elapsed",state.formalStart<0?-1:state.last-state.formalStart,"openingMeals",state.formalMeals,"maxHunger",state.maxHunger,"maxSevereTicks",state.maxSevereTicks));
            if(village!=null){
                out.put("physical",physical());out.put("expected",expected());out.put("growth",nbt(growth()));
                var needs=server.overworld().getDataStorage().get(NeedRequestData.FACTORY,NeedRequestData.NAME);
                out.put("needs",needs==null?null:nbt(needs.save(new CompoundTag(),server.registryAccess())));
                out.put("life",nbt(NpcLifeData.get(server).save(new CompoundTag(),server.registryAccess())));out.put("rights",nbt(RightsStateData.get(server).save(new CompoundTag(),server.registryAccess())));
                var people=new ArrayList<Object>();for(int i=0;i<3;i++)if(server.overworld().getEntity(NaturalVillageLifeData.body(village,i)) instanceof NpcBase n){
                    people.add(Map.of("body",n.getUUID(),"person",n.getCharacterId(),"position",n.position().toString(),"bag",nbt(wrap(NaturalVillageEconomyData.items(n.getPersonalInventory(),server.registryAccess()))),"plan",String.valueOf(com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData.get(server).active(n.getUUID()).orElse(null))));
                }out.put("people",people);
                if(server.overworld().getEntity(NaturalVillageLifeData.body(village,2)) instanceof NpcBase worker){
                    int index=growth().getInt("Placed");var pieces=GrowthLayout.house(village);
                    if(index<pieces.size()){
                        var target=pieces.get(index).positions().getFirst();var route=FieldworkRoutes.plan(worker,target,false);
                        out.put("constructionApproach",Map.of("target",target.toShortString(),"visibleAndInReach",FieldworkRoutes.closeEnough(worker,target),"stand",route==null?"NO_REACHABLE_STAND":route.stand().toShortString(),"navigationDone",worker.getNavigation().isDone(),"workerPosition",worker.position().toString()));
                    }
                }
                out.put("unrelatedNearbyDrops",server.overworld().getEntitiesOfClass(ItemEntity.class,new AABB(origin).inflate(40)).stream().filter(d->!state.opening.containsKey(BuiltInRegistries.ITEM.getKey(d.getItem().getItem()).toString())&&!state.net.containsKey(BuiltInRegistries.ITEM.getKey(d.getItem().getItem()).toString())).map(d->Map.of("id",d.getUUID(),"item",d.getItem().toString(),"position",d.position().toString())).toList());
            }
            out.put("usedHeap",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());out.put("maxHeap",Runtime.getRuntime().maxMemory());out.put("tickNanos",Arrays.stream(server.getTickTimesNanos()).average().orElse(0));return out;
        }
        Object nbt(CompoundTag t){return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE,t);}
        CompoundTag wrap(ListTag list){var t=new CompoundTag();t.put("Items",list);return t;}
        void emit(String type,Object data)throws Exception{Files.writeString(events,JSON.toJson(Map.of("type",type,"tick",server.getWorldData().overworldData().getGameTime(),"utc",Instant.now().toString(),"data",data))+"\n",StandardOpenOption.APPEND);}
        void stop(String why)throws Exception{reason=why;emit("BEFORE_NORMAL_SAVE_STOP",snapshot());stopping=true;server.halt(false);}
        void fail(Exception ex){try{reason="FAILED: "+ex;emit("FAILURE",Map.of("exception",ex.toString(),"snapshot",snapshot()));stopping=true;server.halt(false);}catch(Exception second){throw new IllegalStateException(second);}}
        void stopped()throws Exception{Files.writeString(root.resolve("episode-"+episode+"-stopped.json"),JSON.toJson(Map.of("normalStop",stopping&&!reason.isEmpty(),"reason",reason.isEmpty()?"UNEXPECTED_STOP":reason,"state",state,"seed",seed,"episode",episode)),StandardOpenOption.CREATE_NEW);}
    }
}
