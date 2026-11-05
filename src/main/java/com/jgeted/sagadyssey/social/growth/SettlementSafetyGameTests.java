package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Controlled native calibration; fixture inputs are not natural encounter frequency evidence. */
@GameTestHolder("sagadyssey_safety")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SettlementSafetyGameTests {
    private static final String MARKER="safety-checkpoint.nbt";
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        if(!(e.getServer() instanceof GameTestServer))return;
        var l=e.getServer().overworld();try {
            l.getStructureManager().getOrCreate(ResourceLocation.parse("sagadyssey_safety:empty")).load(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    TagParser.parseTag("{size:[16,8,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        }catch(Exception x){throw new IllegalStateException(x);}
    }
    @BeforeBatch(batch="safety") public static void setup(ServerLevel l) {
        l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());
        l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,l.getServer());l.setDayTime(6000);
        l.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
        l.getServer().tickRateManager().requestGameToSprint(60000);
    }
    @GameTestGenerator public static Collection<TestFunction> tests() {
        String phase=System.getProperty("sagadyssey.safety.phase","baseline");
        if(phase.equals("resume"))return List.of(test("complete_original_lighting_after_process_restart",SettlementSafetyGameTests::resume,18000));
        if(phase.equals("checkpoint"))return List.of(test("lighting_inflight_checkpoint",h->lighting(h,true),18000));
        if(phase.equals("shared"))return List.of(
                test("existing_wounded_retreat_no_free_healing",com.jgeted.sagadyssey.npc.brain.command.NpcDutyGameTests::plannedRetreatHasFiniteMovementWithoutFreeHealing,200),
                test("existing_work_interrupt_and_material_reacquisition",com.jgeted.sagadyssey.npc.brain.command.NpcDutyGameTests::blacksmithBattleDoesNotAdvanceWorkAndReleasesThenReacquiresMaterials,800),
                test("existing_medical_consent_and_real_consumption",com.jgeted.sagadyssey.npc.brain.command.NpcDutyGameTests::consentedMedicalDutyActuallyMovesAndConsumesOnce,450),
                test("existing_last_arrow_no_duplicate",com.jgeted.sagadyssey.npc.brain.command.NpcDutyGameTests::lastRealArrowLaunchConsumesExactlyOneAndCannotRepeat,60));
        if(phase.equals("night"))return List.of(test("night_darkness_refuge_then_daytime_work",SettlementSafetyGameTests::night,8000));
        if(phase.equals("supply"))return List.of(test("household_materials_reach_public_craft_and_light",h->lighting(h,false),18000));
        return List.of(test("lighting_materials_work_and_authority",h->lighting(h,false),18000),
                test("ordinary_zombie_targets_enabled_civilian",SettlementSafetyGameTests::civilian,5000),
                test("equipped_original_guard_defends_with_cost",SettlementSafetyGameTests::guard,5000),
                test("no_material_no_free_torches",SettlementSafetyGameTests::noMaterial,2400));
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body,int limit) {
        return new TestFunction("safety",name,"sagadyssey_safety:empty",limit,0,true,body);
    }
    private static NaturalVillageBuilding fixture(GameTestHelper h,int offset) {
        var b=NaturalVillageR1GameTests.fixture(h,offset,GrowthLayout.COORDINATED);
        h.getLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,h.getLevel().getServer());
        h.getLevel().setDayTime(6000);return b;
    }
    private static void initialDarkness(ServerLevel l,NaturalVillageBuilding b) {
        int removed=0;
        for(var local:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(25,9,21))) {
            var p=NaturalVillageLayout.at(b,local);
            if(l.getBlockState(p).is(Blocks.GLOWSTONE)){l.setBlockAndUpdate(p,Blocks.GLASS.defaultBlockState());removed++;}
        }
        Sagadyssey.LOGGER.info("SAFETY_INITIAL_GEOMETRY village={} removedPresetGlowstone={} replacement=glass oldCottageTorchesRetained=true",b.buildingKey(),removed);
    }
    private static boolean ready(GameTestHelper h,NaturalVillageBuilding b) {
        var d=SettlementSafetyData.existing(h.getLevel().getServer());return d!=null&&d.site(b.buildingKey())!=null;
    }
    private static int count(Container c,Item type){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(type))n+=c.getItem(i).getCount();return n;}
    private static int completed(CompoundTag row) {int n=0;for(String key:row.getCompound("Tasks").getAllKeys())if(row.getCompound("Tasks").getCompound(key).getString("State").equals("DONE"))n++;return n;}
    private static void lighting(GameTestHelper h,boolean checkpoint) {
        var b=fixture(h,0);var l=h.getLevel();boolean[] configured={false};long[] begin={0};int[] dark={0};
        h.onEachTick(()-> {
            if(!ready(h,b))return;var s=l.getServer();var d=SettlementSafetyData.get(s);var row=d.site(b.buildingKey());
            if(!configured[0]) {
                initialDarkness(l,b);
                var official=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b)).headCharacterId();
                h.assertTrue(d.activate(s,b.buildingKey(),UUID.randomUUID(),l.getGameTime()).equals("UNAUTHORIZED"),"outsider cannot expand existing permit");
                h.assertTrue(d.activate(s,b.buildingKey(),official,l.getGameTime()).equals("ALREADY_ACTIVE"),"repeat enable preserves task IDs");
                // Finite public initial inputs. No further supplies after this point.
                boolean household=System.getProperty("sagadyssey.safety.phase","").equals("supply");
                var inventory=NaturalPolityData.inventory(s,b,household?1:3);inventory.setItem(20,new ItemStack(Items.COAL,16));inventory.setItem(21,new ItemStack(household?Items.STICK:Items.OAK_PLANKS,8));
                for(int i=0;i<3;i++)NaturalVillageR1GameTests.actor(l,b,i).setCommand(NpcCommand.WORK);
                configured[0]=true;begin[0]=l.getGameTime();
                Sagadyssey.LOGGER.info("SAFETY_INITIAL village={} source={} coal=16 otherInput={} quantity=8 randomSpawning=false daylightFrozen=6000",b.buildingKey(),household?"household":"public",household?"stick":"oak_planks");return;
            }
            if(row.getInt("DarkFloor")>0&&dark[0]==0)dark[0]=row.getInt("DarkFloor");
            if((l.getGameTime()-begin[0])%1000==0)Sagadyssey.LOGGER.info("SAFETY_PROGRESS ticks={} dark={} tasks={} worker={}",l.getGameTime()-begin[0],row.getInt("DarkFloor"),row.getCompound("Tasks"),NaturalVillageR1GameTests.actor(l,b,2).getWorkActivityDetail());
            if(checkpoint) {
                for(String key:row.getCompound("Tasks").getAllKeys()) {
                    var t=row.getCompound("Tasks").getCompound(key);
                    if(!t.getBoolean("MaterialCarried")||t.getInt("Placed")>0)continue;
                    var marker=new CompoundTag();marker.put("Village",row.getCompound("Village").copy());marker.putString("Task",key);
                    marker.putUUID("Person",t.getUUID("Person"));marker.putUUID("Body",t.getUUID("Body"));marker.putInt("BeforeDark",dark[0]);marker.putLong("Tick",l.getGameTime());
                    marker.put("Safety",d.save(new CompoundTag(),s.registryAccess()));
                    try {NbtIo.writeCompressed(marker,s.getWorldPath(LevelResource.ROOT).resolve(MARKER));s.saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}
                    catch(Exception ex){throw new IllegalStateException(ex);}
                    Sagadyssey.LOGGER.info("SAFETY_CHECKPOINT task={} person={} body={} materialCarried=true tick={}",key,t.getUUID("Person"),t.getUUID("Body"),l.getGameTime());h.succeed();return;
                }
            } else if(completed(row)>=2&&row.getInt("DarkFloor")<dark[0]) {
                int placed=0,carried=0;for(String key:row.getCompound("Tasks").getAllKeys()){var t=row.getCompound("Tasks").getCompound(key);placed+=t.getInt("Placed");if(t.getBoolean("MaterialCarried"))carried++;}
                int torches=count(NaturalPolityData.inventory(s,b,3),Items.TORCH);
                for(int i=0;i<3;i++)torches+=count(NaturalVillageR1GameTests.actor(l,b,i).getPersonalInventory(),Items.TORCH);
                int remainingCoal=0;for(int i=0;i<5;i++)remainingCoal+=count(NaturalPolityData.inventory(s,b,i),Items.COAL);
                for(int i=0;i<3;i++)remainingCoal+=count(NaturalVillageR1GameTests.actor(l,b,i).getPersonalInventory(),Items.COAL);
                int coal=16-remainingCoal;
                h.assertTrue(coal>0&&coal*4==torches+placed,"coal becomes exactly four torches each, in store/body/world; carried not double counted");
                h.assertTrue(!d.permitted(row,NaturalVillageLayout.at(b,new BlockPos(40,1,40))),"mining range is not a light building permit");
                if(System.getProperty("sagadyssey.safety.phase","").equals("supply")) {
                    int deliveries=0;for(String key:row.getCompound("Tasks").getAllKeys())deliveries+=row.getCompound("Tasks").getCompound(key).getInt("SupplyCycle");
                    h.assertTrue(deliveries>=2,"coal and stick both moved through original personal inventory using formal transfer receipts");
                    Sagadyssey.LOGGER.info("SAFETY_SUPPLY_ACCEPT deliveredBatches={} originalHouseholdCoalRemaining={}",deliveries,count(NaturalPolityData.inventory(s,b,1),Items.COAL));
                }
                var saved=d.save(new CompoundTag(),s.registryAccess());h.assertTrue(SettlementSafetyData.load(saved,s.registryAccess()).save(new CompoundTag(),s.registryAccess()).equals(saved),"safety references survive serialization (separate from process test)");
                Sagadyssey.LOGGER.info("SAFETY_LIGHT_ACCEPT ticks={} darkBefore={} darkNow={} placed={} remainingTorches={} coalConsumed={} carried={}",l.getGameTime()-begin[0],dark[0],row.getInt("DarkFloor"),placed,torches,coal,carried);h.succeed();
            }
        });
    }
    private static Zombie zombie(ServerLevel l,NaturalVillageBuilding b,BlockPos local) {
        var z=EntityType.ZOMBIE.create(l);var p=NaturalVillageLayout.at(b,local);z.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);
        z.setPersistenceRequired();z.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
        // Helmet is a declared calibration input to prevent sunlight confounding the target check.
        l.addFreshEntity(z);return z;
    }
    private static void civilian(GameTestHelper h) {
        var b=fixture(h,512);var l=h.getLevel();Zombie[] z={null};NpcBase[] victim={null};long[] begin={0};double[] start={0};boolean[] acquired={false};
        h.onEachTick(()-> {
            if(!ready(h,b))return;
            if(z[0]==null) {
                victim[0]=NaturalVillageR1GameTests.actor(l,b,1);
                // Declared finite entrance approach. The zombie begins outside the refuge, not inside it.
                for(var local:BlockPos.betweenClosed(new BlockPos(10,0,22),new BlockPos(14,0,30))) {
                    var p=NaturalVillageLayout.at(b,local);l.getChunkAt(p);l.setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());
                    l.setBlockAndUpdate(p.above(),Blocks.AIR.defaultBlockState());l.setBlockAndUpdate(p.above(2),Blocks.AIR.defaultBlockState());
                }
                var at=NaturalVillageLayout.at(b,new BlockPos(12,1,22));victim[0].moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);
                z[0]=zombie(l,b,new BlockPos(12,1,28));begin[0]=l.getGameTime();start[0]=victim[0].distanceToSqr(NaturalVillageLayout.at(b,new BlockPos(4,1,14)).getCenter());return;
            }
            if(z[0].getTarget() instanceof NpcBase target&&SettlementSafetyData.enabled(target))acquired[0]=true;
            if((l.getGameTime()-begin[0])%200==0)Sagadyssey.LOGGER.info("SAFETY_REFUGE_PROGRESS ticks={} alive={} health={} position={} threat={} refuge={} activity={} acquired={}",l.getGameTime()-begin[0],victim[0].isAlive(),victim[0].getHealth(),victim[0].blockPosition(),SettlementSafetyRuntime.threat(victim[0])!=null,SettlementSafetyRuntime.refuge(victim[0]),victim[0].getWorkActivityDetail(),acquired[0]);
            h.assertTrue(victim[0].getTarget()!=z[0],"ordinary courier does not chase zombie");
            boolean safetyPlan=com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.data(l.getServer()).active(victim[0].getUUID())
                    .filter(p->p.providerId().equals(SettlementSafetyTask.ID)).isPresent()
                    ||com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.data(l.getServer()).history(victim[0].getUUID()).stream().anyMatch(p->p.providerId().equals(SettlementSafetyTask.ID));
            var door=l.getBlockState(NaturalVillageLayout.at(b,new BlockPos(12,1,21)));
            if(acquired[0]&&safetyPlan&&l.getGameTime()-begin[0]>=500&&victim[0].isAlive()
                    &&SettlementSafetyRuntime.sheltered(victim[0],victim[0].blockPosition())
                    &&door.is(Blocks.OAK_DOOR)&&!door.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)
                    &&victim[0].distanceToSqr(NaturalVillageLayout.at(b,new BlockPos(4,1,14)).getCenter())<start[0]-2) {
                Sagadyssey.LOGGER.info("SAFETY_CIVILIAN_ACCEPT ticks={} zombie={} person={} actualTarget={} health={} movedToRefuge=true",l.getGameTime()-begin[0],z[0].getUUID(),victim[0].getCharacterId(),z[0].getTarget()==null?"NONE":z[0].getTarget().getUUID(),victim[0].getHealth());h.succeed();
            }
        });
    }
    private static void guard(GameTestHelper h) {
        var b=fixture(h,1024);var l=h.getLevel();Zombie[] z={null};NpcBase[] guard={null};long[] begin={0};boolean[] acquired={false};String[] originalTask={null};
        h.onEachTick(()-> {
            if(!ready(h,b))return;
            var data=SettlementSafetyData.get(l.getServer());var row=data.site(b.buildingKey());
            if(guard[0]==null) {
                initialDarkness(l,b);
                guard[0]=NaturalVillageR1GameTests.actor(l,b,2);guard[0].setProfession(NpcProfession.WARRIOR);
                guard[0].setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));guard[0].setCommand(NpcCommand.WORK);
                guard[0].setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
                guard[0].setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.IRON_CHESTPLATE));
                guard[0].setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.IRON_LEGGINGS));
                guard[0].setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
                NaturalVillageR1GameTests.actor(l,b,0).setCommand(NpcCommand.STAY);
                NaturalVillageR1GameTests.actor(l,b,1).setCommand(NpcCommand.STAY);
                NaturalPolityData.inventory(l.getServer(),b,3).setItem(20,new ItemStack(Items.TORCH,4));
                begin[0]=l.getGameTime();return;
            }
            if(z[0]==null) {
                for(String key:row.getCompound("Tasks").getAllKeys()) {
                    var task=row.getCompound("Tasks").getCompound(key);
                    if(!task.getBoolean("MaterialCarried")||!task.hasUUID("Person")||!task.getUUID("Person").equals(guard[0].getCharacterId()))continue;
                    originalTask[0]=key;
                    z[0]=EntityType.ZOMBIE.create(l);z[0].moveTo(guard[0].getX()+3,guard[0].getY(),guard[0].getZ(),0,0);
                    z[0].setPersistenceRequired();z[0].setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));l.addFreshEntity(z[0]);
                    Sagadyssey.LOGGER.info("SAFETY_DEFENCE_START task={} person={} zombie={} originalTorchCarried=true tick={}",key,guard[0].getCharacterId(),z[0].getUUID(),l.getGameTime());return;
                }
                return;
            }
            if(z[0].getTarget() instanceof NpcBase)acquired[0]=true;
            var task=row.getCompound("Tasks").getCompound(originalTask[0]);
            if((l.getGameTime()-begin[0])%200==0)Sagadyssey.LOGGER.info("SAFETY_DEFENCE_PROGRESS ticks={} alive={} health={} zombieHealth={} activity={} task={}",l.getGameTime()-begin[0],guard[0].isAlive(),guard[0].getHealth(),z[0].getHealth(),guard[0].getWorkActivityDetail(),task.getString("Reason"));
            if(acquired[0]&&!z[0].isAlive()&&guard[0].isAlive()&&task.getString("State").equals("DONE")) {
                int wear=guard[0].getMainHandItem().getDamageValue();
                h.assertTrue(wear>0||guard[0].getHealth()<guard[0].getMaxHealth(),"native combat has actual weapon or health cost");
                h.assertTrue(task.getInt("Placed")==1&&!task.getBoolean("MaterialCarried"),"original interrupted job resumes real work with original material");
                Sagadyssey.LOGGER.info("SAFETY_DEFENCE_ACCEPT ticks={} person={} zombie={} health={} weaponDamage={} originalTask={} placed={}",l.getGameTime()-begin[0],guard[0].getCharacterId(),z[0].getUUID(),guard[0].getHealth(),wear,originalTask[0],task.getInt("Placed"));h.succeed();
            }
        });
    }
    private static void resume(GameTestHelper h) {
        var l=h.getLevel();var s=l.getServer();final CompoundTag marker;
        try{marker=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve(MARKER),NbtAccounter.unlimitedHeap());}
        catch(Exception ex){throw new IllegalStateException("Full prior checkpoint required",ex);}
        var b=NaturalVillageLifeData.decode(marker.getCompound("Village"));NaturalVillageR1GameTests.visiting(l,b,true);
        h.onEachTick(()-> {
            var d=SettlementSafetyData.existing(s);if(d==null)return;var row=d.site(b.buildingKey());if(row==null)return;
            var actor=l.getEntity(marker.getUUID("Body"));if(!(actor instanceof NpcBase n))return;
            h.assertTrue(n.getCharacterId().equals(marker.getUUID("Person")),"same body and person after real process restart");
            var task=row.getCompound("Tasks").getCompound(marker.getString("Task"));
            if(!task.getString("State").equals("DONE"))return;
            h.assertTrue(task.getInt("Placed")==1&&!task.getBoolean("MaterialCarried"),"original carried material consumed once");
            Sagadyssey.LOGGER.info("SAFETY_RESTART_ACCEPT task={} person={} placed={} light={} tick={}",marker.getString("Task"),n.getCharacterId(),task.getInt("Placed"),task.getInt("MeasuredBlockLight"),l.getGameTime());h.succeed();
        });
    }
    private static void noMaterial(GameTestHelper h) {
        var b=fixture(h,1536);var l=h.getLevel();boolean[] setup={false};long[] begin={0};
        h.onEachTick(()-> {
            if(!ready(h,b))return;
            if(!setup[0]){initialDarkness(l,b);setup[0]=true;begin[0]=l.getGameTime();return;}
            if(l.getGameTime()-begin[0]<1400)return;
            var row=SettlementSafetyData.get(l.getServer()).site(b.buildingKey());
            h.assertTrue(row.getInt("DarkFloor")>0&&completed(row)==0,"unfunded dark area remains a real gap");
            boolean reason=false;for(String key:row.getCompound("Tasks").getAllKeys()) {
                var t=row.getCompound("Tasks").getCompound(key);h.assertTrue(t.getInt("Placed")==0,"no free light without actual input");
                reason|=t.getString("Reason").contains("缺少");
            }
            h.assertTrue(reason,"waiting has a specific material reason");
            Sagadyssey.LOGGER.info("SAFETY_NO_MATERIAL_ACCEPT dark={} completed=0 reason=missing_legal_inputs",row.getInt("DarkFloor"));h.succeed();
        });
    }
    private static void night(GameTestHelper h) {
        var b=fixture(h,0);var l=h.getLevel();boolean[] setup={false},dawn={false};long[] begin={0};NpcBase[] worker={null};
        h.onEachTick(()-> {
            if(!ready(h,b))return;
            if(!setup[0]) {
                initialDarkness(l,b);var refuge=NaturalVillageLayout.at(b,new BlockPos(5,1,14));l.setBlockAndUpdate(refuge,Blocks.TORCH.defaultBlockState());
                worker[0]=NaturalVillageR1GameTests.actor(l,b,2);
                var start=NaturalVillageLayout.at(b,new BlockPos(18,1,11));worker[0].moveTo(start.getX()+.5,start.getY(),start.getZ()+.5,0,0);
                NaturalPolityData.inventory(l.getServer(),b,3).setItem(20,new ItemStack(Items.TORCH,4));
                for(int i=0;i<2;i++)NaturalVillageR1GameTests.actor(l,b,i).setCommand(NpcCommand.STAY);
                l.setDayTime(18000);setup[0]=true;begin[0]=l.getGameTime();return;
            }
            if(!dawn[0]&&l.getGameTime()-begin[0]>=800) {
                h.assertTrue(SettlementSafetyRuntime.sheltered(worker[0],worker[0].blockPosition()),"original worker physically reached illuminated covered refuge at night");
                l.setDayTime(6000);dawn[0]=true;Sagadyssey.LOGGER.info("SAFETY_NIGHT_REFUGE person={} tick={} declaredDawn=true",worker[0].getCharacterId(),l.getGameTime());
            }
            if(dawn[0]) {
                var r=SettlementSafetyData.get(l.getServer()).site(b.buildingKey());
                for(String key:r.getCompound("Tasks").getAllKeys()) {
                    var t=r.getCompound("Tasks").getCompound(key);
                    if(t.getString("State").equals("DONE")&&t.hasUUID("Person")&&t.getUUID("Person").equals(worker[0].getCharacterId())) {
                        Sagadyssey.LOGGER.info("SAFETY_DAY_RESUME_ACCEPT person={} task={} placed={}",worker[0].getCharacterId(),key,t.getInt("Placed"));h.succeed();return;
                    }
                }
            }
        });
    }
}
