package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import java.nio.file.Files;
import java.util.*;

/** 真实实体装备与载量预检；不以测试替身宣称劳动占用、进食或付款已接通。 */
@GameTestHolder("sagadyssey_war_m2")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "sagadyssey")
public final class WarMobilizationGameTests {
    private static final Identifier EMPTY = Identifier.parse("sagadyssey_war_m2:empty");
    private static boolean enabled() { return Boolean.getBoolean("sagadyssey.war.m2.test"); }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!enabled() || !(event.getServer() instanceof GameTestServer)) return;
        try {
            var level = event.getServer().overworld();
            level.getStructureManager().getOrCreate(EMPTY).load(level.holderLookup(Registries.BLOCK),
                    TagParser.parseCompoundFully("{size:[8,4,8],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
        } catch (Exception exception) { throw new IllegalStateException("war M2 test template failed", exception); }
    }
    @GameTestGenerator public static Collection<TestFunction> tests() {
        if (!enabled()) return List.of();
        return List.of(new TestFunction("war_m2", "mounted_loadout_and_backup", EMPTY.toString(), 200, 0, true, WarMobilizationGameTests::equipment),
                new TestFunction("war_m2", "actual_cargo_capacity", EMPTY.toString(), 200, 0, true, WarMobilizationGameTests::cargo),
                new TestFunction("war_m2", "unbound_militia_has_no_effect", EMPTY.toString(), 200, 0, true, WarMobilizationGameTests::unavailable));
    }
    private static NpcBase fixture(GameTestHelper helper, NpcProfession profession) {
        var npc = helper.spawn(NpcEntityTypes.NPC_BASE.get(), new BlockPos(1, 1, 1));
        npc.setNoAi(true); npc.setNoGravity(true); npc.setProfession(profession);
        for (EquipmentSlot slot : EquipmentSlot.values()) npc.setItemSlot(slot, ItemStack.EMPTY);
        npc.getPersonalInventory().clearContent(); npc.getTransportInventory().clearContent(); return npc;
    }
    private static void equipment(GameTestHelper helper) {
        var npc = fixture(helper, NpcProfession.BLACKSMITH);
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        npc.getPersonalInventory().setItem(6, new ItemStack(Items.IRON_SWORD));
        var horse = helper.spawn(EntityType.HORSE, new BlockPos(3, 1, 1)); horse.setNoAi(true); horse.setNoGravity(true);
        horse.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.IRON_HORSE_ARMOR));
        helper.assertTrue(npc.startRiding(horse, true, false), "fixture rides original horse");
        long revision = npc.getPersonalInventory().revision();
        var target = new WarLoadout(WarLoadout.Mount.HEAVY, WarLoadout.Weapon.BOW, false, false);
        var observed = WarEquipmentInspector.inspect(npc, WarLoadout.Weapon.BOW);
        helper.assertTrue(observed.observed().equals(target) && observed.mismatches(target).isEmpty(), "naked archer with armored horse and real backup");
        helper.assertTrue(npc.getProfession() == NpcProfession.BLACKSMITH && npc.getPersonalInventory().revision() == revision, "inspection preserves profession and inventory");
        npc.getPersonalInventory().setItem(5, new ItemStack(Items.BOW)); npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        helper.assertTrue(WarEquipmentInspector.inspect(npc, WarLoadout.Weapon.BOW).observed().primaryWeapon() == WarLoadout.Weapon.BOW, "drawing backup retains bow specialization");
        horse.setItemSlot(EquipmentSlot.BODY, ItemStack.EMPTY);
        helper.assertTrue(WarEquipmentInspector.inspect(npc, WarLoadout.Weapon.BOW).observed().mount() == WarLoadout.Mount.LIGHT, "horse armor is independent of rider armor");
        npc.stopRiding();
        helper.assertTrue(WarEquipmentInspector.inspect(npc, WarLoadout.Weapon.BOW).observed().mount() == WarLoadout.Mount.FOOT, "dismount is observed without changing profession");
        var ports = WarNativeBindings.currentBuild();
        var spear = ports.items().inspect(new ItemStack(Items.IRON_SPEAR));
        helper.assertTrue(spear.weapons().contains(WarLoadout.Weapon.SPEAR), "actual 1.21.11 vanilla spear tag is recognized");
        helper.assertTrue(ports.actors().support(com.jgeted.sagadyssey.social.war.api.WarAttackKind.SPEAR_JAB).implemented() && spear.melee().isPresent(), "native spear components provide the real melee profile");
        helper.succeed();
    }
    private static void cargo(GameTestHelper helper) {
        var npc = fixture(helper, NpcProfession.COURIER); var bread = new ItemStack(Items.BREAD);
        helper.assertTrue(WarCargoCapacity.inspect(npc, bread, false).cargo() == 27L * bread.getMaxStackSize(), "actual27 full stacks are available");
        npc.getTransportInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        helper.assertTrue(WarCargoCapacity.inspect(npc, bread, false).cargo() == 26L * bread.getMaxStackSize(), "existing cargo occupies space");
        npc.setProfession(NpcProfession.TRADER);
        helper.assertTrue(WarCargoCapacity.inspect(npc, bread, false).cargo() == 8L * bread.getMaxStackSize(), "trader uses only first9 slots");
        npc.setProfession(NpcProfession.BLACKSMITH); long revision = npc.getPersonalInventory().revision();
        helper.assertTrue(WarCargoCapacity.inspect(npc, bread, false).cargo() == 0, "military role cannot bypass zero-slot civilian cargo rule");
        helper.assertTrue(npc.getProfession() == NpcProfession.BLACKSMITH && npc.getPersonalInventory().revision() == revision, "inspection does not reclassify or move items");
        helper.succeed();
    }
    private static void unavailable(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var root = server.getWorldPath(LevelResource.ROOT);
        helper.assertTrue(WarServerEvents.existing(server).isEmpty(), "unused world has no war runtime");
        var request = new WarMilitiaRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InitialWarDefinitions.TROOPS.getFirst(), 1, WarMilitiaRequest.Kind.MILITIA, WarMilitiaRequest.Role.COMBATANT,
                new WarMilitiaRequest.Wage(WarKey.parse("minecraft:emerald"), 1, 24_000), helper.getLevel().getGameTime());
        var preview = WarServerEvents.previewMilitia(server, request);
        helper.assertTrue(!preview.ready() && preview.missing().size() == 10, "all missing shared capabilities are explicit");
        helper.assertTrue(WarServerEvents.existing(server).isEmpty()
                && Files.notExists(root.resolve("data/sagadyssey_war.dat"))
                && Files.notExists(root.resolve("data/" + WarMobilizationRuntime.FILE_NAME)), "preview creates no runtime, service record or file");
        helper.succeed();
    }
}
