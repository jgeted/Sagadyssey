package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.settlement.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;
import net.neoforged.neoforge.gametest.*;


/** Only fixture initialization / declared knowledge event; all forging and transfers are ordinary NPC tasks. */
@GameTestHolder("sagadyssey_pickaxe_batches") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class PickaxeBatchGameTests {
    private static final BlockPos BASE=new BlockPos(4096,80,4096);
    private static final TicketType<ChunkPos> TICKS=TicketType.create("pickaxe-batch",Comparator.comparingLong(ChunkPos::toLong));
    private static CompoundTag prior;
    private static String phase(){return System.getProperty("sagadyssey.pickaxe.phase","disabled");}
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!Boolean.getBoolean("sagadyssey.pickaxe.enabled"))return List.of();
        return switch(phase()){
            case "component" -> List.of(test("two_demand_one_unit_per_order",PickaxeBatchGameTests::component,100));
            case "two" -> List.of(test("real_two_unit_forging",h->run(h,"two"),6000));
            case "partial" -> List.of(test("partial_then_known_source",h->run(h,"partial"),6000));
            case "insufficient" -> List.of(test("one_unit_keeps_real_shortage",h->run(h,"insufficient"),1800));
            case "restart" -> List.of(test("same_two_units_after_process_restart",PickaxeBatchGameTests::restart,800));
            default -> List.of();
        };
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body,int ticks){return new TestFunction("pickaxe_batches","pickaxe_batches."+name,"sagadyssey_pickaxe_batches:empty",ticks,0,true,body);}
    @net.neoforged.bus.api.SubscribeEvent public static void boot(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        prior=null;if(!(e.getServer() instanceof GameTestServer)||!Boolean.getBoolean("sagadyssey.pickaxe.enabled"))return;
        try{var s=e.getServer();var l=s.overworld();l.getStructureManager().getOrCreate(ResourceLocation.parse("sagadyssey_pickaxe_batches:empty"))
            .load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[32,12,32],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));rules(l,0);
            if(phase().equals("restart")){var p=s.getWorldPath(LevelResource.ROOT);prior=NbtIo.readCompressed(p.resolve("pickaxe-batch-checkpoint.nbt"),NbtAccounter.unlimitedHeap());var saved=NbtIo.readCompressed(p.resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");s.getWorldData().overworldData().setGameTime(saved.getLong("Time")+1);force(l);}
        }catch(Exception x){throw new IllegalStateException("complete original batch world required",x);}
    }
    private static void rules(ServerLevel l,int random){l.setDayTime(1000);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,l.getServer());l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(random,l.getServer());}
    private static void force(ServerLevel l){var cp=new ChunkPos(BASE);for(int x=cp.x-1;x<=cp.x+2;x++)for(int z=cp.z-1;z<=cp.z+2;z++){var p=new ChunkPos(x,z);l.setChunkForced(x,z,true);l.getChunk(x,z);l.getChunkSource().addRegionTicket(TICKS,p,2,p,true);}}
    private static BlockPos at(int x,int y,int z){return BASE.offset(x,y,z);}
    private static void floor(GameTestHelper h,int random){var l=h.getLevel();force(l);rules(l,random);for(int x=0;x<30;x++)for(int z=0;z<24;z++)l.setBlockAndUpdate(at(x,-1,z),Blocks.STONE.defaultBlockState());}
    private static NpcBase npc(GameTestHelper h,int x,int z,NpcProfession role){var l=h.getLevel();var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.setPos(at(x,0,z).getX()+.5,BASE.getY(),at(x,0,z).getZ()+.5);n.setProfession(role);n.setPersistenceRequired();n.setCommand(NpcCommand.WORK);n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);n.getPersonalInventory().setItem(5,new ItemStack(Items.BREAD,8));n.setWorkZone(at(x,0,z),at(x,0,z));l.addFreshEntity(n);NpcLifeData.get(l.getServer()).ensureRegistered(n,l.getGameTime());var a=SubjectRef.character(n.getCharacterId());RightsStateData.get(l.getServer()).ensureContext(ContainerIds.npcPersonal(n.getCharacterId()),"npc:"+n.getCharacterId()+":personal",a,a,AccessPolicy.PRIVATE);NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;}
    private static UUID family(GameTestHelper h,String name,List<NpcBase> people){var l=h.getLevel();var s=l.getServer();var site=SettlementRegistryData.get(s).create(s,name,SettlementType.VILLAGE,l.dimension().location().toString(),BASE,40,"sagadyssey:kingdom",people.getFirst().getCharacterId(),l.getGameTime());var homes=HouseholdData.get(s);var home=homes.create(name,l.dimension().location().toString(),site.id(),people.getFirst().getCharacterId(),BASE,l.getGameTime());for(int i=1;i<people.size();i++)h.assertTrue(homes.addMember(home.id(),people.get(i).getCharacterId(),HouseholdRole.KIN,l.getGameTime()),"same real household");return home.id();}
    private static FieldworkStorage point(GameTestHelper h,BlockPos pos,SubjectRef owner,TransferIntent intent){var l=h.getLevel();l.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());var household=owner.type()==SubjectRef.SubjectType.HOUSEHOLD?HouseholdData.get(l.getServer()).byId(owner.id()):null;var policy=household!=null&&pos.equals(household.sharedStorage())?AccessPolicy.HOUSEHOLD:AccessPolicy.PRIVATE;var id=ProductionContainerIds.resolve(l.getServer(),l.dimension().location().toString(),pos);RightsStateData.get(l.getServer()).ensureContext(id,"industry-ab:"+pos.asLong(),owner,owner,policy);var p=new FieldworkStorage(l.dimension().location().toString(),pos,id,owner,policy,intent,"industry-ab-initial-source");SupplyFlowData.get(l.getServer()).register(p);return p;}
    private static Container box(GameTestHelper h,FieldworkStorage p){return SupplyFlowService.container(h.getLevel().getServer(),p);}
    private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private static ItemFingerprint fp(String item){return new ItemFingerprint("minecraft:"+item,0);}
    private static void grant(GameTestHelper h,FieldworkStorage source,SubjectRef to,String reason){RightsStateData.get(h.getLevel().getServer()).grant(new RightGrant(IndustryTransfers.id(source.containerId()+":"+to),source.containerId(),to,Set.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),reason,h.getLevel().getGameTime(),null,true));}
    private static void hire(NpcBase worker,NpcBase owner,long now){NpcLifeData.get(worker.getServer()).onRecruited(worker,owner.getCharacterId(),now);}
    private static void bed(GameTestHelper h,NpcBase n,int x,int z){var l=h.getLevel();var pos=at(x,0,z);var foot=Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT);l.setBlockAndUpdate(pos,foot);l.setBlockAndUpdate(pos.south(),foot.setValue(BedBlock.PART,BedPart.HEAD));h.assertTrue(NpcLifeData.get(l.getServer()).assignBed(n.getCharacterId(),l.dimension().location().toString(),pos.south(),"finite AB test home",l.getGameTime()),"actual bed assignment");}
    private static void sprint(GameTestHelper h,int ticks){try{h.getLevel().getServer().getCommands().getDispatcher().execute("tick sprint "+ticks,h.getLevel().getServer().createCommandSourceStack().withPermission(4).withSuppressedOutput());}catch(Exception x){throw new IllegalStateException(x);}}
    private static void save(GameTestHelper h,CompoundTag m){try{NbtIo.writeCompressed(m,h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("pickaxe-batch-checkpoint.nbt"));h.getLevel().getServer().saveEverything(true,false,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}catch(Exception x){throw new IllegalStateException(x);}}
    private static NpcBase original(GameTestHelper h,String key){var e=h.getLevel().getEntity(prior.getUUID(key));h.assertTrue(e instanceof NpcBase,"original "+key+" body must load, no replacement");var n=(NpcBase)e;h.assertTrue(n.getCharacterId().equals(prior.getUUID(key+"Person")),"same "+key+" persistent person");return n;}
    private static void actor(CompoundTag m,String name,NpcBase n){m.putUUID(name,n.getUUID());m.putUUID(name+"Person",n.getCharacterId());}


    private record Scene(List<NpcBase> people,UUID home,FieldworkStorage target,FieldworkStorage source,UUID need,long started){}
    private static Scene scene(GameTestHelper h,String kind){
        floor(h,0);var s=h.getLevel().getServer();long now=h.getLevel().getGameTime();
        var smith=npc(h,12,5,NpcProfession.BLACKSMITH);var courier=npc(h,3,5,NpcProfession.COURIER);var a=npc(h,7,10,NpcProfession.WORKER);var b=npc(h,10,10,NpcProfession.WORKER);
        var people=List.of(smith,courier,a,b);var home=family(h,"four actual household members",people);var principal=SubjectRef.household(home);
        h.assertTrue(HouseholdData.get(s).assignSharedStorage(home,h.getLevel().dimension().location().toString(),at(14,0,5),at(14,0,5)),"formal household target");
        var target=point(h,at(14,0,5),principal,TransferIntent.DELIVERY);var source=point(h,at(4,0,5),principal,TransferIntent.DELIVERY);
        h.getLevel().setBlockAndUpdate(at(12,0,6),Blocks.ANVIL.defaultBlockState());
        int iron=kind.equals("two")?6:3;int coal=kind.equals("two")?2:1;
        box(h,target).setItem(0,new ItemStack(Items.IRON_INGOT,iron));box(h,target).setItem(1,new ItemStack(Items.COAL,coal));
        if(kind.equals("partial")){box(h,source).setItem(0,new ItemStack(Items.IRON_INGOT,3));box(h,source).setItem(1,new ItemStack(Items.COAL));}
        for(int i=0;i<people.size();i++)bed(h,people.get(i),3+i*3,15);
        int actualDeficit=HouseholdData.get(s).provisionPlan(home).inspect(box(h,target)).toolDeficit();
        h.assertTrue(actualDeficit==2,"four actual household members require two working tools by existing provision rule");
        var need=SupplyFlowService.request(s,principal,"household:tools",target,fp("iron_pickaxe"),actualDeficit,now,now+24000);
        h.assertTrue(need.requested()==2&&need.delivered()==0,"normal four-member household actually requests two tools");
        SupplyFlowService.match(s,now);
        com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("PICKAXE_INITIAL kind={} tick={} home={} need={} actors={} iron={} coal={} futureSourceIron={} futureSourceCoal={} sourceKnown=false",kind,now,home,need.id(),people.stream().map(n->n.getCharacterId()+"/"+n.getUUID()).toList(),iron,coal,kind.equals("partial")?3:0,kind.equals("partial")?1:0);
        return new Scene(people,home,target,source,need.id(),now);
    }
    private static List<ProductionBatch> completed(GameTestHelper h,Scene f){return ProductionBatchData.get(h.getLevel().getServer()).batches().stream().filter(x->x.outputOwner().equals(SubjectRef.household(f.home()))&&x.recipeId().equals("sagadyssey:iron_pickaxe")&&x.status()==ProductionBatchStatus.COMPLETED).toList();}
    private static void component(GameTestHelper h){
        var f=scene(h,"component");for(var n:f.people())n.setCommand(NpcCommand.STAY);
        var s=h.getLevel().getServer();var root=SupplyFlowData.get(s).production(f.need());var order=ProductionOrderData.get(s).byId(root).orElseThrow();
        h.assertTrue(order.output().quantity()==1&&order.inputs().getFirst().quantity()==3&&order.requiredWorkTicks()==80,"two-tool demand must start one physical pick per actual unit of work, not a nonstackable count2 batch");
        for(int i=0;i<20;i++)SupplyFlowService.match(s,h.getLevel().getGameTime());
        h.assertTrue(ProductionOrderData.get(s).byId(root).orElseThrow().equals(order)&&NeedRequestData.get(s).request(f.need()).requested()==2,"repeat dispatch keeps fixed root and original total2");
        com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("PICKAXE_COMPONENT need={} root={} unitOutput=1 totalDemand=2",f.need(),root);h.succeed();
    }
    private static void run(GameTestHelper h,String kind){
        var f=scene(h,kind);var s=h.getLevel().getServer();sprint(h,6500);final boolean[] partialSeen={false};
        if(kind.equals("partial")){
            h.runAtTickTime(1100,()->{var r=NeedRequestData.get(s).request(f.need());h.assertTrue(r.delivered()==1&&r.remaining()==1&&completed(h,f).size()==1&&count(box(h,f.target()),Items.IRON_PICKAXE)==1,"first actual unit delivered; original missing unit1 retained before supply event");partialSeen[0]=true;com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("PICKAXE_PARTIAL tick={} need={} delivered=1 remaining=1 realTools=1",h.getLevel().getGameTime(),f.need());});
            h.runAtTickTime(1200,()->{SupplyFlowData.get(s).reveal(SubjectRef.household(f.home()),f.source().containerId());com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("PICKAXE_KNOWLEDGE_RECOVERED tick={} source={} initialIron=3 initialCoal=1 noGoodsAdded=true",h.getLevel().getGameTime(),f.source().containerId());});
        }
        if(kind.equals("insufficient")){
            h.runAtTickTime(1600,()->{var r=NeedRequestData.get(s).request(f.need());h.assertTrue(r.delivered()==1&&r.remaining()==1&&r.reserved()==0&&r.inTransit()==0,"no stock leaves real missing1, never claim complete2");h.assertTrue(completed(h,f).size()==1&&count(box(h,f.target()),Items.IRON_PICKAXE)==1&&count(box(h,f.target()),Items.IRON_INGOT)==0&&count(box(h,f.target()),Items.COAL)==0,"only real inputs3+1 consumed,actual tool1");com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("PICKAXE_INSUFFICIENT tick={} need={} delivered=1 remaining=1 batches=1",h.getLevel().getGameTime(),f.need());h.succeed();});return;
        }
        h.succeedWhen(()->{
            var r=NeedRequestData.get(s).request(f.need());h.assertTrue(r.delivered()==2&&r.status()==NeedRequestStatus.DELIVERED,"original total2 genuinely fulfilled: "+r);
            if(kind.equals("partial"))h.assertTrue(partialSeen[0]&&h.getLevel().getGameTime()-f.started()<=3600,"normal recovery within2400 ticks after1200 knowledge event, polling included");
            h.assertTrue(count(box(h,f.target()),Items.IRON_PICKAXE)==2&&count(box(h,f.target()),Items.IRON_INGOT)==0&&count(box(h,f.target()),Items.COAL)==0,"two separate physical tools,exact total inputs6+2 consumed");
            h.assertTrue(count(box(h,f.source()),Items.IRON_INGOT)==0&&count(box(h,f.source()),Items.COAL)==0,"no hidden source remainder for input accounting");
            var batches=completed(h,f);h.assertTrue(batches.size()==2&&batches.stream().allMatch(x->x.output().quantity()==1&&x.requiredWorkTicks()==80),"two independent single-tool production receipts");
            for(var x:batches){var order=ProductionOrderData.get(s).ordersForWorker(x.workerId()).stream().filter(o->o.batchId().equals(x.id())).findFirst().orElseThrow();h.assertTrue(ProductionLaborData.get(s).worked(order.id())==80,"each tool required80 actual NPC work ticks");}
            for(var material:NeedRequestData.get(s).requests())if(material.key().purpose().contains(":production:")){
                var matching=batches.stream().map(x->ProductionOrderData.get(s).ordersForWorker(x.workerId()).stream().filter(o->o.batchId().equals(x.id())).findFirst().orElseThrow())
                        .filter(o->material.key().purpose().equals(o.outputOwner().type()+":production:"+o.id())).findFirst();
                if(matching.isPresent()&&material.reserved()==0&&material.inTransit()==0)
                    h.assertTrue(material.status().terminal(),"completed unit leaves no uncommitted extra raw-input pickup request");
            }
            var mark=new CompoundTag();mark.putUUID("Need",f.need());mark.putUUID("Home",f.home());mark.put("Stock",f.target().save());mark.putLong("Time",h.getLevel().getGameTime());for(int i=0;i<f.people().size();i++)actor(mark,"Actor"+i,f.people().get(i));save(h,mark);
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("PICKAXE_TWO_COMPLETED kind={} tick={} start={} elapsed={} need={} batches={} root={} inputIron=6 inputCoal=2 products=2 actualLabor=160",kind,h.getLevel().getGameTime(),f.started(),h.getLevel().getGameTime()-f.started(),f.need(),batches.stream().map(ProductionBatch::id).toList(),SupplyFlowData.get(s).production(f.need()));
        });
    }
    private static void restart(GameTestHelper h){
        if(prior==null)throw new IllegalStateException("complete original two-tool checkpoint required");sprint(h,1000);
        h.runAtTickTime(600,()->{for(int i=0;i<4;i++)original(h,"Actor"+i);var s=h.getLevel().getServer();var r=NeedRequestData.get(s).request(prior.getUUID("Need"));var stock=FieldworkStorage.load(prior.getCompound("Stock"));h.assertTrue(r.delivered()==2&&r.reserved()==0&&r.inTransit()==0,"same root demand still fulfilled2");h.assertTrue(count(box(h,stock),Items.IRON_PICKAXE)==2,"no extra tool after restart/repeat scheduler");h.assertTrue(ProductionBatchData.get(s).batches().stream().filter(x->x.outputOwner().equals(SubjectRef.household(prior.getUUID("Home")))&&x.status()==ProductionBatchStatus.COMPLETED).count()==2,"no third manufacture after restart");com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("PICKAXE_RESTART_ACCEPT tick={} need={} samePeople=4 products=2 batches=2",h.getLevel().getGameTime(),r.id());h.succeed();});
    }
}
