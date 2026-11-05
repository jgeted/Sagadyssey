package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithRestPlanFactory;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
public final class BlacksmithRestGameTests {
    @GameTest(template="fieldwork_empty", batch="phase9_blacksmith_rest", timeoutTicks=500)
    public static void completedForgeRestsThenAutomaticallyClaimsNextRealOrder(GameTestHelper h) {
        var smith = NpcDutyGameTests.npc(h, NpcProfession.BLACKSMITH, 4);
        var first = NpcDutyGameTests.order(h, smith);
        ProductionOrder[] second = {null};
        long[] queuedAt = {-1};
        h.succeedWhen(() -> {
            var orders = ProductionOrderData.get(smith.getServer());
            h.assertTrue(orders.byId(first.id()).orElseThrow().status() == ProductionOrderStatus.COMPLETED, "original forge must actually finish");
            var box = (Container) h.getLevel().getBlockEntity(first.targetContainerPosition());
            if (second[0] == null) {
                h.assertTrue(NpcBrainRuntime.data(smith.getServer()).active(smith.getUUID()).isPresent(), "next tick selects post-work rest");
                var rest = NpcBrainRuntime.data(smith.getServer()).active(smith.getUUID()).orElseThrow();
                h.assertTrue(rest.providerId().equals(BlacksmithRestPlanFactory.PROVIDER), "finished work gets explicit finite rest without invented facilities");
                h.assertTrue(RightsStateData.get(smith.getServer()).reservationsForToken("production:" + first.batchId(), h.getLevel().getGameTime()).isEmpty(), "completed inputs released before rest");
                box.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
                box.setItem(1, new ItemStack(Items.COAL));
                long now = h.getLevel().getGameTime();
                second[0] = ProductionOrder.open(UUID.randomUUID(), first.issuer(), first.issuer(), first.recipeId(), first.inputs(), first.output(), 80, UUID.randomUUID(), first.workstationDimension(), first.workstationPosition(), first.targetContainerId(), first.targetContainerDimension(), first.targetContainerPosition(), now, "new authorized order during finite rest");
                orders.put(second[0]); queuedAt[0] = now;
            }
            h.assertTrue(orders.byId(second[0].id()).orElseThrow().status() == ProductionOrderStatus.COMPLETED, "new order automatically discovered after rest, not explicitly assigned by test");
            h.assertTrue(h.getLevel().getGameTime() - queuedAt[0] <= 220, "finite rest cannot starve a new order");
            int picks = 0; for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(Items.IRON_PICKAXE)) picks += box.getItem(i).getCount();
            h.assertTrue(picks == 2 && ProductionLaborData.get(smith.getServer()).worked(second[0].id()) == 80, "exactly two actual products and actual work; rest created no assets");
        });
    }

    @GameTest(template="fieldwork_empty", batch="phase9_blacksmith_rest", timeoutTicks=340)
    public static void restRepeatsWithFiniteHistoryAndStopsOnCommandChange(GameTestHelper h) {
        var smith = NpcDutyGameTests.npc(h, NpcProfession.BLACKSMITH, 4); smith.setCommand(NpcCommand.WORK);
        h.runAtTickTime(260, () -> {
            var data = NpcBrainRuntime.data(smith.getServer());
            h.assertTrue(data.active(smith.getUUID()).orElseThrow().providerId().equals(BlacksmithRestPlanFactory.PROVIDER), "still explicitly resting beyond old 200-tick warning threshold");
            h.assertTrue(data.history(smith.getUUID()).stream().filter(p -> p.providerId().equals(BlacksmithRestPlanFactory.PROVIDER) && p.status() == com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus.SUCCEEDED).count() >= 2, "rest ended and was reselected, not one immortal wait");
            smith.setCommand(NpcCommand.STAY);
        });
        h.runAtTickTime(280, () -> {
            h.assertTrue(NpcBrainRuntime.data(smith.getServer()).active(smith.getUUID()).stream().noneMatch(p -> p.providerId().equals(BlacksmithRestPlanFactory.PROVIDER)), "manual STAY cancels occupational rest");
            h.assertTrue(smith.getPersonalInventory().isEmpty(), "rest does not invent food or output"); h.succeed();
        });
    }

    @GameTest(template="fieldwork_empty", batch="phase9_blacksmith_rest", timeoutTicks=180)
    public static void severeHungerInterruptsRestAndConsumesOnlyRealFood(GameTestHelper h) {
        var smith = NpcDutyGameTests.npc(h, NpcProfession.BLACKSMITH, 4); smith.setCommand(NpcCommand.WORK);
        smith.getPersonalInventory().setItem(0, new ItemStack(Items.BREAD));
        h.runAtTickTime(10, () -> {
            h.assertTrue(NpcBrainRuntime.data(smith.getServer()).active(smith.getUUID()).orElseThrow().providerId().equals(BlacksmithRestPlanFactory.PROVIDER), "precondition: actual rest running");
            NpcLifeData.get(smith.getServer()).setHunger(smith.getCharacterId(), 850, h.getLevel().getGameTime());
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getTick() > 30 && NpcLifeData.get(smith.getServer()).needs(smith.getCharacterId()).hunger() < 800, "finite rest cannot block urgent survival");
            h.assertTrue(smith.getPersonalInventory().count(s -> s.is(Items.BREAD)) == 0, "one actual bread consumed");
        });
    }
}
