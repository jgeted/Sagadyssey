package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import static com.jgeted.sagadyssey.social.industry.IndustryCompletionGameTests.*;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.industry.common.IndustryTransfers;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Finite original logs, one original 24-plank need and normal automatic batch dispatch. */
@GameTestHolder("sagadyssey_industries_completion") @PrefixGameTestTemplate(false)
public final class WoodBatchGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!Boolean.getBoolean("sagadyssey.industry.native")||!System.getProperty("sagadyssey.completion.phase","").equals("wood-batches"))return List.of();
        return List.of(new TestFunction("wood_batches","internal.one_need_two_birch_batches","sagadyssey_industries_completion:empty",8000,0,true,WoodBatchGameTests::batches));
    }
    private static void batches(GameTestHelper h){
        floor(h,0);var level=h.getLevel();var server=level.getServer();long start=level.getGameTime();
        var worker=npc(h,3,6,NpcProfession.WORKER);family(h,"bounded birch workshop",List.of(worker));bed(h,worker,4,18);
        var person=worker.getCharacterId();var body=worker.getUUID();var owner=SubjectRef.character(person);
        var stock=point(h,at(15,0,6),owner,TransferIntent.DELIVERY);level.setBlockAndUpdate(at(16,0,6),Blocks.CRAFTING_TABLE.defaultBlockState());
        h.assertTrue(NpcLifeData.get(server).assignPrivateStorage(server,person,stock.dimension(),stock.position(),start),"original private stock assigned");
        box(h,stock).setItem(0,new ItemStack(Items.BIRCH_LOG,6));SupplyFlowData.get(server).reveal(owner,stock.containerId());
        var original=SupplyFlowService.request(server,owner,"wood-batches:planks",stock,fp("birch_planks"),24,start,start+8000);
        UUID[] stickNeed={null};long[] firstComplete={-1},stableSince={-1};
        Sagadyssey.LOGGER.info("WOOD_BATCH_INITIAL tick={} person={} body={} need={} stock={} birchLogs=6 bread=8 planks=0 sticks=0 work=200-per-batch deadline=8000",start,person,body,original.id(),stock.containerId());
        sprint(h,8500);
        h.onEachTick(()->{
            if(level.getGameTime()%20==0&&java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("industry-stop-request.json")))h.fail("frozen wall budget stop; preserve original wood world");
            if(stickNeed[0]==null&&NeedRequestData.get(server).request(original.id()).status()==NeedRequestStatus.DELIVERED){
                firstComplete[0]=level.getGameTime();
                h.assertTrue(count(box(h,stock),Items.BIRCH_PLANKS)==24,"both original native batches physically in stock before downstream demand");
                stickNeed[0]=SupplyFlowService.request(server,owner,"wood-batches:sticks",stock,fp("stick"),4,level.getGameTime(),start+8000).id();
                Sagadyssey.LOGGER.info("WOOD_BATCH_DOWNSTREAM tick={} originalNeed={} stickNeed={} initialPlanks=24 requestedSticks=4",level.getGameTime(),original.id(),stickNeed[0]);
            }
        });
        h.succeedWhen(()->{
            h.assertTrue(worker.isAlive()&&worker.getCharacterId().equals(person)&&worker.getUUID().equals(body),"original living worker");
            h.assertTrue(stickNeed[0]!=null,"original 24 plank need completed before formal downstream need");
            var needs=NeedRequestData.get(server);var first=needs.request(original.id());var last=needs.request(stickNeed[0]);
            h.assertTrue(first.status()==NeedRequestStatus.DELIVERED&&first.requested()==24&&first.delivered()==24&&first.reserved()==0&&first.inTransit()==0,"same original need really received 24 once");
            h.assertTrue(last.status()==NeedRequestStatus.DELIVERED&&last.delivered()==4&&last.reserved()==0&&last.inTransit()==0,"four real sticks received once");
            var orders=ProductionOrderData.get(server).ordersForWorker(person);
            var planks=orders.stream().filter(o->o.recipeId().equals(WorkshopTask.PLANKS)).toList();var sticks=orders.stream().filter(o->o.recipeId().equals(WorkshopTask.STICKS)).toList();
            h.assertTrue(orders.size()==3&&planks.size()==2&&sticks.size()==1,"exactly two plank batches and one downstream stick batch");
            var expectedRoot=IndustryTransfers.id("industry-planks:"+person+":need:"+original.id());
            var expectedSecond=IndustryTransfers.id("industry-planks:"+person+":need:"+original.id()+":batch:1");
            h.assertTrue(planks.stream().map(ProductionOrder::id).collect(java.util.stream.Collectors.toSet()).equals(Set.of(expectedRoot,expectedSecond)),"same Need root and derived second batch");
            h.assertTrue(SupplyFlowData.get(server).production(original.id()).equals(expectedRoot),"original persisted association never replaced");
            for(var order:orders){
                var batch=ProductionBatchData.get(server).batch(order.batchId());
                h.assertTrue(order.status()==ProductionOrderStatus.COMPLETED&&person.equals(order.assignedWorkerId())&&order.outputOwner().equals(owner)&&order.targetContainerId().equals(stock.containerId()),"original performer owner and destination");
                h.assertTrue(batch!=null&&batch.status()==ProductionBatchStatus.COMPLETED&&batch.workerId().equals(person)&&batch.outputOwner().equals(owner)&&batch.containerId().equals(stock.containerId())&&batch.inputs().equals(order.inputs())&&batch.output().equals(order.output())&&batch.recipeId().equals(order.recipeId())&&batch.requiredWorkTicks()==200,"matching actual original batch");
                h.assertTrue(ProductionLaborData.get(server).worked(order.id())==200,"200 actual work ticks per original order");
                boolean plank=order.recipeId().equals(WorkshopTask.PLANKS);
                h.assertTrue(order.inputs().equals(List.of(new ProductionMaterial(fp(plank?"birch_log":"birch_planks"),plank?3:2))),"actual birch material selected, no hardcoded oak input");
                h.assertTrue(order.output().equals(new ProductionMaterial(fp(plank?"birch_planks":"stick"),plank?12:4)),"native output quantity");
                receipt(h,needs,order.id(),plank?original.id():stickNeed[0],plank?12:4);
            }
            h.assertTrue(count(box(h,stock),Items.BIRCH_LOG)==0&&count(box(h,stock),Items.BIRCH_PLANKS)==22&&count(box(h,stock),Items.STICK)==4,"six logs become 24 planks, two consumed into four sticks");
            h.assertTrue(worker.getPersonalInventory().count(i->i.is(Items.BIRCH_LOG)||i.is(Items.BIRCH_PLANKS)||i.is(Items.STICK))==0,"no hidden production stock in original bag");
            h.assertTrue(RightsStateData.get(server).context(stock.containerId()).defaultBeneficialOwner().equals(owner),"unchanged stock ownership");
            h.assertTrue(orders.stream().allMatch(o->RightsStateData.get(server).reservationsForToken(o.token(),level.getGameTime()).isEmpty()&&RightsStateData.get(server).reservationsForToken("production:"+o.batchId(),level.getGameTime()).isEmpty()),"completed original reservations released");
            if(stableSince[0]<0)stableSince[0]=level.getGameTime();
            h.assertTrue(level.getGameTime()-stableSince[0]>=300,"three further normal aggregation intervals cannot repeat production or receipts");
            Sagadyssey.LOGGER.info("WOOD_BATCH_ACCEPT tick={} elapsed={} person={} originalNeed={} secondBatch={} stickNeed={} firstComplete={} inputs=6 realPlankBatches=2 realStickBatches=1 actualLabor=600 finalPlanks=22 finalSticks=4 duplicateObservation=300",level.getGameTime(),level.getGameTime()-start,person,original.id(),expectedSecond,stickNeed[0],firstComplete[0]);
            save(h,new CompoundTag());
        });
    }
    private static void receipt(GameTestHelper h,NeedRequestData data,UUID order,UUID need,int quantity){
        var journal=MinecraftApi.listOfType(data.save(new CompoundTag(),null), "Receipts",Tag.TAG_COMPOUND);
        for(String stage:List.of("reserve","dispatch","delivery")){
            UUID id=SupplyFlowService.receipt(order,"produced-"+stage);int matches=0;
            for(var raw:journal){var row=(CompoundTag)raw;if(row.read("Id", UUIDUtil.CODEC).orElseThrow().equals(id)){
                matches++;h.assertTrue(row.read("Request", UUIDUtil.CODEC).orElseThrow().equals(need)&&row.getIntOr("Quantity", 0)==quantity,"once-only receipt belongs to original need and native quantity");
                h.assertTrue(row.getStringOr("Action", "").equals(stage.equals("delivery")?"DELIVER":stage.toUpperCase(java.util.Locale.ROOT)),"correct receipt action");
            }}h.assertTrue(matches==1,"exactly one original "+stage+" receipt");
        }
    }
}
