package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.work.ServiceProvision.Step.*;

class ServiceProvisionTest {
    private static ServiceProvision.Terms terms(long rate, long period) {
        return new ServiceProvision.Terms(UUID.randomUUID(), "test:service", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SubjectRef.character(UUID.randomUUID()),
                ItemLocation.block(UUID.randomUUID(), "minecraft:overworld", BlockPos.ZERO), new ItemFingerprint("minecraft:emerald", 0), rate, period);
    }
    @Test void wagesAccrueByElapsedTimeAndOnlyTheFinalFractionRoundsUp() {
        var value = ServiceProvision.configured(terms(3, 10), 0).mark(OPEN_PAYROLL, UUID.randomUUID(), 0, 0);
        assertEquals(BigInteger.ZERO, value.due(3)); assertEquals(BigInteger.valueOf(3), value.due(10)); assertEquals(BigInteger.valueOf(6), value.due(20));
        value = value.mark(CLOSE_PAYROLL, UUID.randomUUID(), 21, 25);
        assertEquals(BigInteger.valueOf(7), value.due(Long.MAX_VALUE)); assertTrue(value.marks().get(CLOSE_PAYROLL).outstanding());
        assertFalse(value.ownsNeeds());
    }
    @Test void hugeLegalTermsDoNotOverflowOrCreateNegativeDebt() {
        var value = ServiceProvision.configured(terms(Long.MAX_VALUE, 1), 0).mark(OPEN_PAYROLL, UUID.randomUUID(), 0, 0);
        var expected = BigInteger.valueOf(Long.MAX_VALUE).pow(2); assertEquals(expected, value.due(Long.MAX_VALUE));
        assertEquals(expected, value.mark(CLOSE_PAYROLL, UUID.randomUUID(), Long.MAX_VALUE, Long.MAX_VALUE).arrears(Long.MAX_VALUE));
    }
    @Test void provisionMarksCannotBeOutOfSequenceOrMoveTimeBackwards() {
        var value = ServiceProvision.configured(terms(1, 10), 10);
        assertThrows(IllegalArgumentException.class, () -> value.mark(SUSTAIN, UUID.randomUUID(), 10, 10));
        var opened = value.mark(OPEN_PAYROLL, UUID.randomUUID(), 10, 10);
        assertThrows(IllegalArgumentException.class, () -> opened.mark(CLOSE_PAYROLL, UUID.randomUUID(), 9, 11));
        var sustained = opened.mark(SUSTAIN, UUID.randomUUID(), 12, 12); assertTrue(sustained.ownsNeeds());
        assertFalse(sustained.mark(END_SUSTAIN, UUID.randomUUID(), 15, 15).ownsNeeds());
    }
    @Test void schemaRoundTripsAndRefusesForeignAndFutureOrDuplicateData() throws Exception {
        var codec = new ServiceProvisionCodec(); var value = ServiceProvision.configured(terms(1, 10), 0).mark(OPEN_PAYROLL, UUID.randomUUID(), 0, 0);
        assertEquals(List.of(value), codec.read(codec.write(List.of(value)))); assertThrows(java.io.IOException.class, () -> codec.write(List.of(value, value)));
        byte[] bytes = codec.write(List.of(value)); java.nio.ByteBuffer.wrap(bytes).putInt(4, 99); assertThrows(java.io.IOException.class, () -> codec.read(bytes));
        assertThrows(java.io.IOException.class, () -> new WarKitCodecProxy().read(codec.write(List.of(value))));
    }
    private static class WarKitCodecProxy { Collection<?> read(byte[] bytes) throws java.io.IOException { return new com.jgeted.sagadyssey.social.war.adapter.minecraft.WarKitCodec().read(bytes); } }
    @Test void personalContainerReferencesCannotBeForgedAndBlockReferencesRoundTrip() {
        var person = UUID.randomUUID(); var body = UUID.randomUUID(); var location = ItemLocation.personal(person, body);
        assertEquals(location, ItemLocation.load(location.save())); var block = termsForBlock(); assertEquals(block, ItemLocation.load(block.save()));
        assertThrows(IllegalArgumentException.class, () -> new ItemLocation(UUID.randomUUID(), ItemLocation.Kind.PERSONAL, body, person, "", 0));
    }
    private static ItemLocation termsForBlock() { return terms(1, 10).treasury(); }
}
