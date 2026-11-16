package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.*;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离真实共享效果的故障用例；不把SimpleContainer视为原生NPC槽位测试。 */
class WarKitAdapterTest {
    @TempDir Path directory;
    private final class Fixture implements AutoCloseable {
        final com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest request = WarMilitiaTestSupport.request();
        final UUID body = UUID.randomUUID(); final ItemLocation armory = ItemLocation.block(UUID.randomUUID(), "minecraft:overworld", BlockPos.ZERO);
        final ItemLocation equipment = ItemLocation.equipment(request.characterId(), body);
        final SimpleContainer stock = new SimpleContainer(3), gear = new SimpleContainer(1);
        final CompoundTag stockTag = new CompoundTag(), gearTag = new CompoundTag();
        final RightsStateData rights = new RightsStateData(); final TransferLedgerData transfers = new TransferLedgerData();
        final WitnessedItemEndpoint stockEndpoint = WitnessedItemEndpoint.forOwner(armory.id(), stock, () -> stockTag, () -> true, stock::setChanged);
        final WitnessedItemEndpoint gearEndpoint = WitnessedItemEndpoint.forOwner(equipment.id(), gear, () -> gearTag, () -> true, gear::setChanged);
        final WarKitRecord plan = new WarKitRecord(request, body, armory, List.of(new WarKitRecord.Line(ItemFingerprint.of(new ItemStack(Items.IRON_SWORD)), 1)), List.of(), List.of());
        PhysicalItemService items; WarKitAdapter kit; long now = 100;
        Fixture() throws Exception {
            var payer = SubjectRef.character(request.actorId()); var person = SubjectRef.character(request.characterId());
            rights.ensureContext(armory.id(), "fixture:armory", payer, payer, AccessPolicy.PRIVATE); rights.ensureContext(equipment.id(), "fixture:gear", person, person, AccessPolicy.PRIVATE);
            stock.setItem(0, new ItemStack(Items.IRON_SWORD)); items = new PhysicalItemService(directory.resolve("items.sgw"), rights, transfers); reopen();
            waitFor(() -> kit.configure(plan) == Status.APPLIED);
        }
        void reopen() throws Exception { kit = new WarKitAdapter(directory.resolve("kits.sgw"), items, rights, location -> location.equals(armory) ? Optional.of(stockEndpoint) : location.equals(equipment) ? Optional.of(gearEndpoint) : Optional.empty(), ignored -> Optional.empty()); }
        Effect effect() { return new Effect(request, WarMobilization.Stage.EQUIP, WarMobilization.operationId(request.id(), WarMobilization.Stage.EQUIP), WarMobilization.cancellationId(request.id(), WarMobilization.Stage.EQUIP), Map.of(), now++); }
        Reply advance() { var effect = effect(); var reply = kit.lookup(effect); return reply.status() == Status.NOT_APPLIED ? kit.apply(effect) : reply; }
        void waitFor(BooleanSupplier done) { long until = System.nanoTime() + 5_000_000_000L; do { if (done.getAsBoolean()) return; LockSupport.parkNanos(1_000_000); } while (System.nanoTime() < until); fail("kit checkpoint stalled"); }
        public void close() { kit.close(); items.close(); }
    }
    @Test void aProvedCapacityRefusalCanCancelWithoutLosingOriginalItemsOrSpinningAttempts() throws Exception {
        try (var f = new Fixture()) {
            f.gear.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            f.waitFor(() -> { f.advance(); var p = f.kit.find(f.request.id()).orElseThrow(); return p.issue().isPresent() && f.items.find(p.issue().orElseThrow().id()).filter(v -> v.phase() == PhysicalItemOperation.Phase.DENIED).isPresent()
                    && f.kit.cancel(f.effect()).status() == Status.NOT_APPLIED; });
            assertEquals(Status.NOT_APPLIED, f.kit.cancel(f.effect()).status()); assertTrue(f.kit.provedUnapplied(f.kit.find(f.request.id()).orElseThrow(), false));
            for (int i = 0; i < 10; i++) assertEquals(Status.UNAVAILABLE, f.advance().status());
            assertEquals(1, f.kit.find(f.request.id()).orElseThrow().issues().size()); assertTrue(f.stock.getItem(0).is(Items.IRON_SWORD)); assertEquals(64, f.gear.getItem(0).getCount());
        }
    }
    @Test void staleRevisionsRetryOnlyAfterThePreviousAttemptHasDurableNoEffectProof() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(Status.PENDING, f.kit.apply(f.effect()).status()); f.rights.bumpContext(f.armory.id());
            f.waitFor(() -> f.advance().status() == Status.APPLIED);
            var record = f.kit.find(f.request.id()).orElseThrow(); assertEquals(2, record.issues().size());
            assertEquals(PhysicalItemOperation.Phase.DENIED, f.items.find(record.issues().getFirst().id()).orElseThrow().phase());
            assertTrue(f.kit.priorAttemptsProvedUnapplied(record)); assertTrue(f.stock.getItem(0).isEmpty()); assertTrue(f.gear.getItem(0).is(Items.IRON_SWORD));
            f.kit.close(); f.reopen(); assertEquals(Status.APPLIED, f.kit.lookup(f.effect()).status()); assertTrue(f.stock.getItem(0).isEmpty());
        }
    }
    @Test void anIssuingCrashRemainsUnknownAndNeverCreatesASecondAttempt() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(Status.PENDING, f.kit.apply(f.effect()).status()); f.kit.close(); f.items.close();
            var record = new WarKitCodec().read(Files.readAllBytes(directory.resolve("kits.sgw"))).iterator().next(); var intent = record.issue().orElseThrow();
            Files.write(directory.resolve("items.sgw"), new PhysicalItemOperationCodec().write(List.of(new PhysicalItemOperation(intent, PhysicalItemOperation.Phase.ISSUING, Optional.empty()))));
            f.items = new PhysicalItemService(directory.resolve("items.sgw"), f.rights, f.transfers); f.reopen();
            assertEquals(Status.UNKNOWN, f.kit.lookup(f.effect()).status()); assertEquals(1, f.kit.find(f.request.id()).orElseThrow().issues().size());
            assertTrue(f.stock.getItem(0).is(Items.IRON_SWORD)); assertTrue(f.gear.getItem(0).isEmpty());
        }
    }
}
