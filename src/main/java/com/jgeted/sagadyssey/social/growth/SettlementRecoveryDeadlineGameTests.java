package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Native recovery clocks. Reads existing schedule snapshots, never refreshes them. */
@GameTestHolder("sagadyssey_development") @PrefixGameTestTemplate(false)
public final class SettlementRecoveryDeadlineGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!SettlementGrowthRetryContractGameTests.phase().matches("counter-v11-g0[1-3]"))return List.of();
        String id=SettlementGrowthRetryContractGameTests.caseId();int budget=id.equals("G01")?24000:id.equals("G02")?30000:18000;
        Sagadyssey.LOGGER.info("V11_SELECTED case={} expected=1",id);
        return List.of(new TestFunction("v11-recovery",id.toLowerCase(Locale.ROOT),"sagadyssey_development:empty",budget,0,true,h->new Run(h).start()));
    }
    static final class Run {
        final GameTestHelper h;final ServerLevel l;final String id=SettlementGrowthRetryContractGameTests.caseId();final List<NaturalVillageBuilding> vs;final CompoundTag proof=new CompoundTag();final ListTag clock=new ListTag();
        long at,initialized=-1,changed=-1,since=-1,failedAt=-1,qualified,unknown,nonqualified;int stage,placedBefore,heldBefore;boolean done,timedOut;String project;BlockPos obstruction;BlockState before;NpcBase courier;
        Run(GameTestHelper h){this.h=h;l=h.getLevel();vs=id.equals("G03")?SettlementGrowthRetryContractGameTests.restored.getList("Villages",Tag.TAG_COMPOUND).stream().map(x->NaturalVillageLifeData.decode((CompoundTag)x)).toList():List.of(NaturalVillageR1GameTests.fixture(h,0,SettlementDevelopmentLayout.TEMPLATE),NaturalVillageR1GameTests.fixture(h,256,NaturalVillageLayout.FARM_V3),NaturalVillageR1GameTests.fixture(h,512,NaturalVillageLayout.FARM_V3));}
        NaturalVillageBuilding target(){return vs.getFirst();}
        void start(){at=l.getGameTime();var s=l.getServer();s.setDifficulty(Difficulty.NORMAL,true);l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s);l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3,s);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(id.equals("G01"),s);if(!id.equals("G01"))l.setDayTime(6000);
            if(id.equals("G03")){SettlementDevelopmentGameTests.releaseObservation(l,vs);SettlementDevelopmentGameTests.observeRoute(l,vs);for(var x:SettlementGrowthRetryContractGameTests.restored.getCompound("Metadata").getList("TemporaryBarrier",Tag.TAG_COMPOUND)){var t=(CompoundTag)x;var p=BlockPos.of(t.getLong("Position"));SettlementGrowthRetryContractGameTests.require(l.getBlockState(p).is(Blocks.GLASS),"declared original glass");l.setBlock(p,NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),t.getCompound("Before")),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);}}
            s.tickRateManager().requestGameToSprint(id.equals("G02")?30000:24000);h.onEachTick(()->{if(done)return;try{tick();}catch(GameTestAssertException x){throw x;}catch(Exception x){finish("HARNESS_ERROR",x.toString(),false);}});
        }
        void event(String e){Sagadyssey.LOGGER.info("V11_CLOCK_EVENT case={} event={} tick={} elapsed={} qualified={} unknown={}",id,e,l.getGameTime(),l.getGameTime()-at,qualified,unknown);}
        boolean transfer(NpcBase n,UUID from,Container src,UUID to,Container dst,Item item,int q,SubjectRef actor,String key){var t=new CompoundTag();boolean ok=SettlementGrowthData.get(l.getServer()).transfer(n,t,ContainerIds.named("v11:"+id+":"+key),actor,TransferIntent.GIFT,List.of(new TransferLeg(from,to,ItemFingerprint.of(new ItemStack(item)),q)),Map.of(from,src,to,dst));proof.put(key,t);return ok;}
        void tick(){var s=l.getServer();long now=l.getGameTime();var natural=NaturalVillageLifeData.existing(s);var d=SettlementDevelopmentData.existing(s);
            if(d==null||natural==null||d.site(target().buildingKey())==null||vs.stream().anyMatch(b->{var v=natural.village(b.buildingKey());return v==null||!v.getBoolean("Ready")||NaturalVillageR1GameTests.actor(l,b,2)==null;})){if(now-at>=1600)finish("UNSUPPORTED","initialization1600 exhausted within total",false);return;}
            if(initialized<0){
                if(!id.equals("G03")){SettlementDevelopmentGameTests.observeRoute(l,vs);SettlementDevelopmentGameTests.configureInitial(l,target(),vs.get(1),vs.get(2));}
                if(!id.equals("G01")){l.setDayTime(6000);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,s);}
                if(id.equals("G02"))for(var b:vs.subList(1,3)){var f=NaturalVillageR1GameTests.actor(l,b,0);var stash=NaturalPolityData.inventory(s,b,0);SettlementGrowthRetryContractGameTests.require(stash.getItem(20).isEmpty(),"empty original stash slot20");stash.setItem(20,f.getPersonalInventory().getItem(1));f.getPersonalInventory().setItem(1,ItemStack.EMPTY);}
                courier=NaturalVillageR1GameTests.actor(l,target(),1);
                if(id.equals("G03")){
                    SettlementGrowthRetryContractGameTests.require(courier.getCharacterId().toString().equals("61b4f774-24d9-3670-a5bd-e9f704655f1c")&&courier.getPersonalInventory().isEmpty(),"exact original empty personal courier");
                    var bag=ContainerIds.npcPersonal(courier.getCharacterId());var pub=NaturalVillageEconomyData.storeId(target(),3);var inv=NaturalPolityData.inventory(s,target(),3);var character=SubjectRef.character(courier.getCharacterId());var rights=RightsStateData.get(s);rights.ensureContext(bag,"npc:"+courier.getCharacterId()+":personal",character,character,AccessPolicy.PRIVATE);
                    if(!transfer(courier,pub,inv,bag,courier.getPersonalInventory(),Items.COBBLESTONE,36,SubjectRef.institution(target().settlementId()),"InitialFiniteCapacityObstacle")){finish("UNSUPPORTED","existing lawful public gift cannot establish declared finite capacity obstacle",false);return;}
                    // Declared pre-observation slot distribution; title stays with the actual recipient.
                    courier.getPersonalInventory().clearContent();for(int i=0;i<36;i++){rights.removeClaimsForSlot(bag,i);courier.getPersonalInventory().setItem(i,new ItemStack(Items.COBBLESTONE));}rights.bumpContext(bag);
                    SettlementGrowthRetryContractGameTests.require(SettlementDevelopmentGameTests.count(inv,Items.COBBLESTONE)==10,"original public stone46 minus36");
                }
                initialized=now;proof.put("Initial",SettlementGrowthRetryContractGameTests.snapshot(l,vs));event("INITIAL_CONFIGURATION_FROZEN");return;
            }
            int total=id.equals("G01")?24000:id.equals("G02")?30000:18000;
            if(now-at>=total-2){finish(timedOut?"FAIL_TIMING":"UNSUPPORTED","frozen total budget exhausted; no extension for preparation or diagnostics",false);return;}
            for(var b:vs)for(int i=0;i<3;i++){var person=CharacterRegistryData.get(s).byId(NaturalVillageLifeData.person(b,i));if(person==null||!person.alive()){finish("FAIL","original actor died or disappeared; preserve actual history",true);return;}}
            var row=d.site(target().buildingKey());var p=SettlementGrowthRetryContractGameTests.firstProject(row);
            if(id.equals("G03")){food(now,row);return;}
            if(id.equals("G02")){
                if(stage==0){
                    if(p!=null){finish("FAIL","expansion began despite missing incoming original hoe",true);return;}
                    var old=SettlementCoordinatorData.existing(s).site(target().buildingKey());if(!old.getCompound("Jobs").getCompound("HOUSE").getString("State").equals("DONE"))return;
                    if(since<0){since=now;return;}if(now-since<2400)return;
                    var b=vs.get(1);var farmer=NaturalVillageR1GameTests.actor(l,b,0);var from=NaturalVillageEconomyData.storeId(b,0);var to=ContainerIds.npcPersonal(farmer.getCharacterId());
                    NaturalVillageR1GameTests.actor(l,target(),1).setCommand(NpcCommand.STAY);NaturalVillageR1GameTests.actor(l,target(),2).setCommand(NpcCommand.STAY);
                    if(!transfer(farmer,from,NaturalPolityData.inventory(s,b,0),to,farmer.getPersonalInventory(),Items.IRON_HOE,1,SubjectRef.household(NaturalVillageLifeData.home(b)),"OriginalHoeReturn")){finish("UNSUPPORTED","original legal hoe restoration failed",false);return;}
                    since=now;stage=1;event("ORIGINAL_HOE_RESTORED_LABOUR_STILL_UNAVAILABLE");return;
                }
                if(stage==1){if(p!=null){finish("FAIL","expansion during true labour conflict",true);return;}if(now-since<2400)return;NaturalVillageR1GameTests.actor(l,target(),1).setCommand(NpcCommand.WORK);NaturalVillageR1GameTests.actor(l,target(),2).setCommand(NpcCommand.WORK);changed=now;stage=2;proof.putLong("ExternalRestored",now);event("LAST_EXTERNAL_WORK_CONDITION_RESTORED");return;}
                measure(now,null);
                if(p!=null&&p.getCompound("Jobs").getCompound("BUILD").getInt("Placed")>0){proof.put("OriginalApplication",d.application(NaturalVillageLifeData.home(vs.get(1))));completeTiming("original project first real place");return;}
                if(qualified>600&&!timedOut){timedOut=true;event("600_QUALIFIED_EXCEEDED_FIRST_FAILURE");proof.put("FirstDeadlineFailure",SettlementGrowthRetryContractGameTests.snapshot(l,vs));}
                if(now-changed>=12000)finish(timedOut?"FAIL_TIMING":"UNSUPPORTED","no original real placement in diagnostic12000; no retrospective v8 verdict",false);return;
            }
            if(p==null)return;var build=p.getCompound("Jobs").getCompound("BUILD");int placed=build.getInt("Placed");
            if(stage==0){if(placed<5||placed>=54||!build.getBoolean("MaterialCarried")||build.getInt("MaterialsHeld")<=0)return;
                obstruction=SettlementDevelopmentLayout.house(target(),p.getInt("Lot")).get(placed).positions().getFirst();before=l.getBlockState(obstruction);if(!before.isAir()){finish("UNSUPPORTED","next legal construction cell already unavailable",false);return;}placedBefore=placed;heldBefore=build.getInt("MaterialsHeld");project=p.getString("Id");since=now;proof.put("BeforeObstruction",SettlementGrowthRetryContractGameTests.snapshot(l,vs));l.setBlock(obstruction,Blocks.GLASS.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);stage=1;event("ACTUAL_TEST_OBSTRUCTION_PLACED");return;
            }
            if(!p.getString("Id").equals(project)){finish("FAIL","original project identity replaced",true);return;}
            if(stage==1){if(placed!=placedBefore||!build.getBoolean("MaterialCarried")||build.getInt("MaterialsHeld")!=heldBefore){finish("FAIL","work or held material changed through obstruction",true);return;}if(now-since<1200)return;l.setBlock(obstruction,before,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);changed=now;stage=2;proof.putLong("ExternalRestored",now);event("EXACT_OBSTRUCTION_REMOVED");return;}
            measure(now,build.hasUUID("Person")?build.getUUID("Person"):null);
            if(placed>placedBefore){completeTiming("same project actual Placed increased with retained original materials");return;}
            if(qualified>600&&!timedOut){timedOut=true;event("600_QUALIFIED_EXCEEDED_FIRST_FAILURE");proof.put("FirstDeadlineFailure",SettlementGrowthRetryContractGameTests.snapshot(l,vs));}
            if(now-changed>=12000)finish(timedOut?"FAIL_TIMING":"UNSUPPORTED","original obstruction continuation diagnostic window expired",false);
        }
        void food(long now,CompoundTag row){var s=l.getServer();var f=SettlementGrowthRetryContractGameTests.food(row);
            if(failedAt<0){
                if(f.hasUUID("LastTransfer")){var receipt=TransferLedgerData.get(s).receipt(f.getUUID("LastTransfer"));if(receipt!=null&&receipt.status()==TransferReceipt.Status.CAPACITY_EXCEEDED){failedAt=receipt.completedAt();proof.putLong("FailureObservedAt",now);proof.putLong("ActualFailureCompletedAt",failedAt);proof.put("GenuineCapacityFailure",SettlementGrowthRetryContractGameTests.snapshot(l,vs));event("NORMAL_FOOD_CAPACITY_FAILURE");if(now==failedAt)return;}}
                if(failedAt<0){if(now-at>=12000)finish("UNSUPPORTED","normal FOOD genuine capacity failure not reached; no invented failure",false);return;}
            }
            if(changed<0){if(now!=failedAt+1){finish("UNSUPPORTED","could not intervene at frozen next tick",false);return;}var from=ContainerIds.npcPersonal(courier.getCharacterId());var to=NaturalVillageEconomyData.storeId(target(),3);
                if(!transfer(courier,from,courier.getPersonalInventory(),to,NaturalPolityData.inventory(s,target(),3),Items.COBBLESTONE,36,SubjectRef.character(courier.getCharacterId()),"Original36StoneReturn")){finish("UNSUPPORTED","lawful finite obstacle removal failed, original obligations preserved",false);return;}changed=now;proof.putLong("ExternalRestored",now);event("EXTERNAL_CAPACITY_RESTORED_INTERNAL_BACKOFF_COUNTS");return;}
            measure(now,courier.getCharacterId());
            if(f.getBoolean("Cargo")&&f.hasUUID("LastTransfer")){var receipt=TransferLedgerData.get(s).receipt(f.getUUID("LastTransfer"));if(receipt!=null&&receipt.successful()){proof.put("ActualFoodPickup",f.copy());completeTiming("same normal FOOD actual pick succeeded");return;}}
            if(qualified>600&&!timedOut){timedOut=true;proof.put("FirstDeadlineFailure",SettlementGrowthRetryContractGameTests.snapshot(l,vs));event("600_QUALIFIED_EXCEEDED_FIRST_FAILURE");}
            if(now-changed>=2400)finish(timedOut?"FAIL_TIMING":"UNSUPPORTED","normal FOOD no observed original pickup by diagnostic end; backoff and unknown retained",false);
        }
        void measure(long now,UUID responsible){var frame=new CompoundTag();frame.putLong("At",now);boolean valid=false,missing=false;var observations=new ListTag();
            for(int i=1;i<=2;i++){var n=NaturalVillageR1GameTests.actor(l,target(),i);if(responsible!=null&&!responsible.equals(n.getCharacterId()))continue;var x=new CompoundTag();x.putUUID("Person",n.getCharacterId());x.putString("Command",n.getCommand().name());var life=NpcLifeData.get(l.getServer());var schedule=life.schedule(n.getCharacterId());var needs=life.needs(n.getCharacterId());
                if(schedule==null||needs==null){missing=true;x.putString("Qualification","UNKNOWN");}
                else{String a=schedule.activity().name();x.putString("Schedule",a);x.putLong("ScheduleUpdated",schedule.updatedAt());x.putLong("ScheduleReview",schedule.reviewAt());x.putInt("Hunger",needs.hunger());
                    boolean staleStay=a.equals("STAY")&&n.getCommand()==NpcCommand.WORK&&schedule.updatedAt()<changed;
                    boolean work=Set.of("WORK","DUTY").contains(a)||staleStay;x.putBoolean("StaleStayPollingCountsAsWork",staleStay);
                    x.putBoolean("Sleeping",n.isSleeping());x.putBoolean("NoAi",n.isNoAi());x.putBoolean("HasAttackTarget",n.getTarget()!=null);
                    var record=life.record(n.getCharacterId());var home=HouseholdData.get(l.getServer()).forMember(n.getCharacterId());var holder=PoliticalStateData.get(l.getServer()).titleHolder(target().settlementId());var rights=RightsStateData.get(l.getServer());
                    boolean loaded=l.getChunkSource().getChunkNow(n.blockPosition().getX()>>4,n.blockPosition().getZ()>>4)!=null;
                    boolean authority=record!=null&&record.authority()==NpcWorkAuthority.INSTITUTION&&target().settlementId().equals(record.activeWorkplaceSettlementId());
                    // Do not call NaturalPolityData.authorized here: it refreshes grants.
                    boolean publicRight=holder!=null&&new RightsService(rights).can(SubjectRef.character(holder),AssetRight.MANAGE,NaturalVillageEconomyData.storeId(target(),3),-1,ItemFingerprint.of(new ItemStack(Items.WHITE_WOOL)),now).status()==RightsVerdict.Status.ALLOW;
                    boolean floor=home!=null&&target().settlementId().equals(home.settlementId())&&SettlementDevelopmentData.capacity(l.getServer(),home).stableFloor();
                    x.putBoolean("Loaded",loaded);x.putBoolean("OriginalWorkAuthority",authority);x.putBoolean("PublicManageRight",publicRight);x.putBoolean("ActualHomeFloor",floor);
                    boolean foodStepReady=true;
                    if(id.equals("G03")){
                        var development=SettlementDevelopmentData.get(l.getServer());var p=SettlementGrowthRetryContractGameTests.firstProject(development.site(target().buildingKey()));
                        var admission=p==null?null:development.application(p.getUUID("Household"));var point=SupplyFlowData.get(l.getServer()).point(NaturalVillageEconomyData.storeId(target(),0));
                        var dest=p==null?null:l.getBlockEntity(SettlementDevelopmentLayout.pantry(target(),p.getInt("Lot")));
                        if(admission==null||point==null||!(dest instanceof Container)){missing=true;foodStepReady=false;x.putString("FoodExternal","UNKNOWN_SOURCE_OR_ORIGINAL_ADMISSION");}
                        else{
                            int available=SupplyFlowService.available(l.getServer(),point,SubjectRef.household(admission.getUUID("Household")),ItemFingerprint.of(new ItemStack(Items.CARROT)),now);
                            int deficit=(int)Math.ceil(admission.getList("People",Tag.TAG_COMPOUND).size()*SettlementPolicy.DAILY_MEALS_PER_PERSON*2)-SettlementDevelopmentGameTests.count((Container)dest,Items.CARROT);
                            foodStepReady=available>0&&deficit>0&&!admission.contains("Departed");x.putInt("ActualLawfulSourceAvailable",available);x.putInt("OriginalFoodDeficit",deficit);x.putString("FoodExternal",foodStepReady?"READY":"ACTUAL_SOURCE_OR_DESTINATION_CHANGED");
                        }
                    }
                    boolean q=n.isAlive()&&!n.isNoAi()&&!n.isSleeping()&&n.getTarget()==null&&n.getCommand()==NpcCommand.WORK&&work&&needs.hunger()<800&&loaded&&authority&&publicRight&&floor&&foodStepReady;
                    valid|=q;x.putString("Qualification",q?"QUALIFIED":"NONQUALIFIED_RECORDED_EXTERNAL_FACTS");}
                observations.add(x);
            }
            if(valid)qualified++;else if(missing||observations.isEmpty())unknown++;else nonqualified++;
            frame.put("People",observations);frame.putLong("Qualified",qualified);frame.putLong("Unknown",unknown);frame.putLong("Nonqualified",nonqualified);clock.add(frame);
        }
        void completeTiming(String event){proof.putString("RealRecoveryEvent",event);proof.putLong("ProgressTick",l.getGameTime());if(qualified>600||timedOut)finish("FAIL_TIMING","eventually resumed, but new600 qualified criterion exceeded; old v8 not retroactively judged",false);else if(unknown>0)finish("UNSUPPORTED_TIMING","actual recovery observed; unknown ticks prevent600 claim",false);else finish("PASS",event+" within600 qualified ticks",false);}
        void finish(String result,String why,boolean hard){if(done)return;done=true;proof.putString("Case",id);proof.putString("Result",result);proof.putString("Reason",why);proof.putBoolean("HardFailure",hard);proof.putLong("Elapsed",l.getGameTime()-at);proof.putLong("Qualified",qualified);proof.putLong("Nonqualified",nonqualified);proof.putLong("Unknown",unknown);proof.put("Clock",clock);proof.put("Final",SettlementGrowthRetryContractGameTests.snapshot(l,vs));SettlementGrowthRetryContractGameTests.write(l,"v11-"+id+"-proof.nbt",proof);Sagadyssey.LOGGER.info("V11_RESULT case={} result={} hard={} elapsed={} qualified={} unknown={} why={}",id,result,hard,l.getGameTime()-at,qualified,unknown,why);if(result.startsWith("FAIL")||result.equals("HARNESS_ERROR"))h.fail(result+": "+why);else h.succeed();}
    }
}
