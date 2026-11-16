package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.work.*;
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
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.work.ServiceProvision.Step.*;

/** 真实共享转移/消费配合隔离容器；服务端NPC全流程另由GameTest覆盖。 */
class ServiceProvisionServiceTest {
    @TempDir Path directory;
    private final class Fixture implements AutoCloseable {
        final UUID person = UUID.randomUUID(), body = UUID.randomUUID(), id = UUID.randomUUID();
        final SubjectRef payer = SubjectRef.character(UUID.randomUUID()), payee = SubjectRef.character(person);
        final ItemLocation bagLocation = ItemLocation.personal(person, body), stockLocation = ItemLocation.block(UUID.randomUUID(), "minecraft:overworld", BlockPos.ZERO);
        final SimpleContainer bag = new SimpleContainer(10), stock = new SimpleContainer(10);
        final CompoundTag bagTag = new CompoundTag(), stockTag = new CompoundTag();
        final RightsStateData rights = new RightsStateData(); final TransferLedgerData transfers = new TransferLedgerData();
        NpcLifeData life = new NpcLifeData(); boolean available = true;
        final Map<ItemLocation,WitnessedItemEndpoint> additional = new HashMap<>();
        final WitnessedItemEndpoint bagEndpoint = WitnessedItemEndpoint.forOwner(bagLocation.id(), bag, () -> bagTag, () -> available, bag::setChanged);
        final WitnessedItemEndpoint stockEndpoint = WitnessedItemEndpoint.forOwner(stockLocation.id(), stock, () -> stockTag, () -> true, stock::setChanged);
        PhysicalItemService physical; ServiceProvisionService costs; long now = 100;
        Fixture(long rate, long period) throws Exception {
            life.ensureNeeds(person, 0); rights.ensureContext(bagLocation.id(), "fixture:bag", payee, payee, AccessPolicy.PRIVATE);
            rights.ensureContext(stockLocation.id(), "fixture:stock", payer, payer, AccessPolicy.PRIVATE);
            physical = new PhysicalItemService(directory.resolve("items.sgw"), rights, transfers); reopenCosts();
            var terms = new ServiceProvision.Terms(id, "fixture:work", UUID.randomUUID(), person, body, payer, stockLocation, ItemFingerprint.of(new ItemStack(Items.EMERALD)), rate, period);
            waitFor(() -> costs.configure(terms, now).status() == ServiceProvisionService.Status.APPLIED);
        }
        void reopenCosts() throws Exception { costs = new ServiceProvisionService(directory.resolve("costs.sgw"), physical, rights, life,
                location -> location.equals(bagLocation) ? available ? Optional.of(bagEndpoint) : Optional.empty() : location.equals(stockLocation) ? Optional.of(stockEndpoint) : Optional.ofNullable(additional.get(location)), terms -> 0); }
        void mark(ServiceProvision.Step step, long at) { UUID op = UUID.randomUUID(); waitFor(() -> costs.mark(id, step, op, at, now).status() == ServiceProvisionService.Status.APPLIED); }
        void waitFor(BooleanSupplier done) { long until = System.nanoTime() + 5_000_000_000L; do { if (done.getAsBoolean()) return; LockSupport.parkNanos(1_000_000); } while (System.nanoTime() < until); fail("checkpoint did not complete"); }
        void pumpUntil(BooleanSupplier done) { waitFor(() -> { costs.tick(now++); return done.getAsBoolean(); }); }
        ServiceProvision value() { return costs.find(id).orElseThrow(); }
        void durable() { waitFor(() -> costs.configure(value().terms(), now).status() == ServiceProvisionService.Status.APPLIED); }
        public void close() { costs.close(); physical.close(); }
    }
    @Test void continuousWagesUseRealItemsAndReopeningCannotPayThemAgain() throws Exception {
        try (var f = new Fixture(1, 10)) {
            f.stock.setItem(0, new ItemStack(Items.EMERALD, 10)); f.mark(OPEN_PAYROLL, 0); f.mark(CLOSE_PAYROLL, 20);
            f.pumpUntil(() -> f.value().paid().equals(BigInteger.TWO)); f.durable(); assertEquals(8, f.stock.getItem(0).getCount()); assertEquals(2, f.bag.getItem(0).getCount());
            f.costs.close(); f.reopenCosts(); for (int i = 0; i < 15; i++) f.costs.tick(f.now++);
            assertEquals(8, f.stock.getItem(0).getCount()); assertEquals(BigInteger.ZERO, f.value().arrears(f.now));
        }
    }
    @Test void debtSurvivesClosureAndLaterRealFundingPaysWithoutHoldingLabor() throws Exception {
        try (var f = new Fixture(1, 10)) {
            f.mark(OPEN_PAYROLL, 0); f.mark(CLOSE_PAYROLL, 40); var close = f.value().marks().get(CLOSE_PAYROLL);
            for (int i = 0; i < 10; i++) f.costs.tick(f.now++); assertEquals(BigInteger.valueOf(4), f.value().arrears(f.now)); assertFalse(f.costs.ownsNeeds(f.person));
            f.stock.setItem(0, new ItemStack(Items.EMERALD, 4)); f.pumpUntil(() -> f.value().arrears(f.now).signum() == 0);
            assertEquals(close, f.value().marks().get(CLOSE_PAYROLL)); assertTrue(close.outstanding()); assertTrue(f.stock.getItem(0).isEmpty());
        }
    }
    @Test void realRationsRestoreOriginalNeedsOnceAcrossAServiceReopen() throws Exception {
        try (var f = new Fixture(1, Long.MAX_VALUE)) {
            f.life.setHunger(f.person, 550, 0); f.bag.setItem(0, new ItemStack(Items.BREAD, 3)); f.mark(OPEN_PAYROLL, 0); f.mark(SUSTAIN, 0);
            f.pumpUntil(() -> f.value().settlements().stream().anyMatch(s -> s.applied() && s.attempt().purpose() == ServiceProvision.Purpose.MEAL)); f.durable();
            assertEquals(2, f.bag.getItem(0).getCount()); assertTrue(f.life.needs(f.person).hunger() < 200); var meal = f.value().settlements().getFirst();
            assertNotNull(f.life.mealReceipt(meal.attempt().intent().id())); f.costs.close(); f.reopenCosts();
            for (int i = 0; i < 20; i++) f.costs.tick(f.now++); assertEquals(2, f.bag.getItem(0).getCount());
        }
    }
    @Test void unloadedBodiesAccrueOnlyBaselineAndNeverConsumeOrResetHunger() throws Exception {
        try (var f = new Fixture(1, Long.MAX_VALUE)) {
            f.life.setHunger(f.person, 300, 0); f.bag.setItem(0, new ItemStack(Items.BREAD, 3)); f.mark(OPEN_PAYROLL, 0); f.mark(SUSTAIN, 0); f.available = false;
            f.costs.tick(1000); assertEquals(310, f.life.needs(f.person).hunger()); f.costs.tick(1000); assertEquals(310, f.life.needs(f.person).hunger()); assertEquals(3, f.bag.getItem(0).getCount());
        }
    }
    @Test void rolledBackNutritionStopsRecoveryInsteadOfFeedingAgain() throws Exception {
        try (var f = new Fixture(1, Long.MAX_VALUE)) {
            f.life.setHunger(f.person, 550, 0); f.bag.setItem(0, new ItemStack(Items.BREAD, 3)); f.mark(OPEN_PAYROLL, 0); f.mark(SUSTAIN, 0);
            f.pumpUntil(() -> !f.value().settlements().isEmpty()); f.durable(); f.costs.close();
            f.life = new NpcLifeData(); f.life.ensureNeeds(f.person, 0); f.life.setHunger(f.person, 550, 0); f.reopenCosts(); f.costs.tick(f.now++);
            assertTrue(f.costs.problem(f.id).orElseThrow().contains("NUTRITION_HISTORY")); assertEquals(550, f.life.needs(f.person).hunger()); assertEquals(2, f.bag.getItem(0).getCount());
        }
    }
    @Test void aPreviouslyFedBodyStillAccruesBaselineWhileUnloadedAfterReopen() throws Exception {
        try (var f = new Fixture(1, Long.MAX_VALUE)) {
            f.life.setHunger(f.person, 550, 0); f.bag.setItem(0, new ItemStack(Items.BREAD, 3)); f.mark(OPEN_PAYROLL, 0); f.mark(SUSTAIN, 0);
            f.pumpUntil(() -> !f.value().settlements().isEmpty()); f.durable(); int before = f.life.needs(f.person).hunger();
            f.costs.close(); f.available = false; f.reopenCosts(); f.costs.tick(1000);
            assertEquals(before + 10, f.life.needs(f.person).hunger()); assertEquals(2, f.bag.getItem(0).getCount());
            assertFalse(f.costs.problem(f.id).orElse("").contains("HISTORY"));
        }
    }
    @Test void foreignTermsAndCommandsCannotReplaceAnAcceptedAgreement() throws Exception {
        try (var f = new Fixture(1, 10)) {
            var t = f.value().terms(); var changed = new ServiceProvision.Terms(t.id(), t.ownerKind(), t.owner(), t.person(), t.body(), t.payer(), t.treasury(), t.wageItem(), 99, t.period());
            assertEquals(ServiceProvisionService.Status.CONFLICT, f.costs.configure(changed, f.now).status()); f.mark(OPEN_PAYROLL, 0);
            assertEquals(ServiceProvisionService.Status.CONFLICT, f.costs.mark(f.id, OPEN_PAYROLL, UUID.randomUUID(), 0, f.now).status());
        }
    }
    @Test void anUnissuedWageForAnUnloadedBodyDoesNotStarveOtherRecipientsOrReplayOnReturn() throws Exception {
        try(var f=new Fixture(1,10)){
            f.stock.setItem(0,new ItemStack(Items.EMERALD,2));f.mark(OPEN_PAYROLL,0);f.mark(CLOSE_PAYROLL,1);
            f.pumpUntil(()->f.value().pending().isPresent());f.durable();var original=f.value().pending().orElseThrow().intent();f.available=false;
            var person=UUID.randomUUID();var body=UUID.randomUUID();var id=UUID.randomUUID();var location=ItemLocation.personal(person,body);var bag=new SimpleContainer(10);var tag=new CompoundTag();var subject=SubjectRef.character(person);
            f.rights.ensureContext(location.id(),"fixture:second",subject,subject,AccessPolicy.PRIVATE);f.additional.put(location,WitnessedItemEndpoint.forOwner(location.id(),bag,()->tag,()->true,bag::setChanged));f.life.ensureNeeds(person,0);
            var terms=new ServiceProvision.Terms(id,"fixture:work",UUID.randomUUID(),person,body,f.payer,f.stockLocation,ItemFingerprint.of(new ItemStack(Items.EMERALD)),1,10);
            f.waitFor(()->f.costs.configure(terms,f.now).status()==ServiceProvisionService.Status.APPLIED);
            for(var step:List.of(OPEN_PAYROLL,CLOSE_PAYROLL)){var op=UUID.randomUUID();f.waitFor(()->f.costs.mark(id,step,op,step==OPEN_PAYROLL?0:1,f.now).status()==ServiceProvisionService.Status.APPLIED);}
            f.pumpUntil(()->f.costs.find(id).orElseThrow().paid().equals(BigInteger.ONE));assertEquals(original,f.value().pending().orElseThrow().intent());assertEquals(1,bag.countItem(Items.EMERALD));assertEquals(1,f.stock.countItem(Items.EMERALD));
            f.available=true;f.pumpUntil(()->f.value().paid().equals(BigInteger.ONE));assertEquals(1,f.bag.countItem(Items.EMERALD));assertTrue(f.stock.isEmpty());assertEquals(1,bag.countItem(Items.EMERALD));
            assertEquals(1,f.value().settlements().stream().filter(s->!s.applied()).count());
        }
    }
    @Test void aFullProvisionStoreRefusesWithoutQueuingAnUnacceptedGhostAgreement() throws Exception {
        try (var f = new Fixture(1, 10)) {
            var terms = f.value().terms(); f.costs.close(); var records = new ArrayList<ServiceProvision>();
            var codec = new ServiceProvisionCodec();
            for (int i = 0; i < codec.capacity(); i++) records.add(ServiceProvision.configured(new ServiceProvision.Terms(UUID.randomUUID(), terms.ownerKind(), terms.owner(), UUID.randomUUID(), UUID.randomUUID(),
                    terms.payer(), terms.treasury(), terms.wageItem(), terms.rate(), terms.period()), 0));
            Files.write(directory.resolve("costs.sgw"), codec.write(records)); f.reopenCosts();
            assertEquals(ServiceProvisionService.Status.UNAVAILABLE, f.costs.configure(terms, f.now).status()); assertTrue(f.costs.find(f.id).isEmpty());
            for (int i = 0; i <= codec.capacity(); i++) { long tick = f.now++; assertDoesNotThrow(() -> f.costs.tick(tick)); }
            assertEquals(codec.capacity(), f.costs.records().size()); assertFalse(Files.exists(directory.resolve("items.sgw")));
        }
    }
}
