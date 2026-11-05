package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
@GameTestHolder("sagadyssey_stock")
@PrefixGameTestTemplate(false)
public final class StockReconciliationGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void installTemplate(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) return;
        var level = event.getServer().overworld();
        try {
            level.getStructureManager().getOrCreate(net.minecraft.resources.ResourceLocation
                    .fromNamespaceAndPath("sagadyssey_stock", "fieldwork_empty"))
                    .load(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                            net.minecraft.nbt.TagParser.parseTag("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        } catch (Exception e) { throw new IllegalStateException("stock test template", e); }
    }
    private record Fixture(SupplyFlowData.Policy policy, Container inventory) {}

    private static Fixture fixture(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var relative = new BlockPos(2, 1, 2);
        h.setBlock(relative, Blocks.BARREL);
        var position = h.absolutePos(relative);
        String dimension = h.getLevel().dimension().location().toString();
        var owner = SubjectRef.character(UUID.randomUUID());
        var id = ProductionContainerIds.resolve(server, dimension, position);
        RightsStateData.get(server).ensureContext(id, dimension + ":" + position.asLong(),
                owner, owner, AccessPolicy.PRIVATE);
        var point = new FieldworkStorage(dimension, position, id, owner, AccessPolicy.PRIVATE,
                TransferIntent.DELIVERY, "stock_reconciliation_fixture");
        var flow = SupplyFlowData.get(server);
        flow.register(point);
        var policy = new SupplyFlowData.Policy("stock-regression:" + UUID.randomUUID(), owner, id,
                new ItemFingerprint("minecraft:coal", 0), 10, "workshop");
        flow.policy(policy);
        return new Fixture(policy, (Container) h.getBlockEntity(relative));
    }

    private static NeedRequest request(GameTestHelper h, Fixture f) {
        return NeedRequestData.get(h.getLevel().getServer()).requests().stream()
                .filter(r -> r.key().purpose().equals("CHARACTER:workshop:" + f.policy().id()))
                .findFirst().orElseThrow();
    }

    @GameTest(template="fieldwork_empty", batch="stock_reconciliation", timeoutTicks=100)
    public static void partialManualSupplyShrinksOriginalRequest(GameTestHelper h) {
        var f = fixture(h);
        var server = h.getLevel().getServer(); long now = h.getLevel().getGameTime();
        SupplyFlowService.aggregate(server, now);
        var original = request(h, f);
        f.inventory().setItem(0, new ItemStack(Items.COAL, 7));
        SupplyFlowService.aggregate(server, now);
        var revised = request(h, f);
        h.assertTrue(revised.id().equals(original.id()) && revised.originalRequested() == 10
                && revised.remaining() == 3 && revised.delivered() == 0,
                "real stock reduces only unmatched demand without fabricating delivery");
        h.assertTrue(f.inventory().getItem(0).getCount() == 7, "aggregation does not consume physical stock");
        SupplyFlowData.get(server).removePolicy(f.policy().id()); h.succeed();
    }

    @GameTest(template="fieldwork_empty", batch="stock_reconciliation", timeoutTicks=100)
    public static void fullManualSupplyStopsFurtherMatching(GameTestHelper h) {
        var f = fixture(h);
        var server = h.getLevel().getServer(); long now = h.getLevel().getGameTime();
        SupplyFlowService.aggregate(server, now);
        var original = request(h, f);
        f.inventory().setItem(0, new ItemStack(Items.COAL, 10));
        SupplyFlowService.aggregate(server, now);
        SupplyFlowService.match(server, now);
        var revised = request(h, f);
        h.assertTrue(revised.id().equals(original.id()) && revised.status() == NeedRequestStatus.CANCELLED
                && revised.delivered() == 0, "full buffer cancels only remaining demand");
        h.assertTrue(SupplyFlowData.get(server).routes().stream().noneMatch(r -> r.need().equals(original.id())),
                "cancelled request cannot generate another shipment");
        h.assertTrue(f.inventory().getItem(0).getCount() == 10, "manual stock stays intact");
        SupplyFlowData.get(server).removePolicy(f.policy().id()); h.succeed();
    }
}
