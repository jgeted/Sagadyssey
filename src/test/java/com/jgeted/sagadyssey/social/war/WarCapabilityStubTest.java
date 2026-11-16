package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarNativeBindings;
import com.jgeted.sagadyssey.social.war.api.WarAttackKind;
import com.jgeted.sagadyssey.social.war.feature.command.WarCapabilityCatalog;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts.AttackStatus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarCapabilityStubTest {
    @Test void allEightImplementationStagesHaveExplicitStatusAndNativeSpearsAreAvailable() {
        var entries = WarCapabilityCatalog.entries();
        assertEquals(EnumSet.allOf(WarCapabilityCatalog.Stage.class), EnumSet.copyOf(entries.stream().map(WarCapabilityCatalog.Entry::stage).toList()));
        assertEquals(entries.size(), entries.stream().map(WarCapabilityCatalog.Entry::id).distinct().count());
        for (var kind : List.of(WarAttackKind.SPEAR_JAB, WarAttackKind.SPEAR_CHARGE, WarAttackKind.MOUNTED_SPEAR_CHARGE)) {
            var entry = entries.stream().filter(e -> e.id().equals(kind.key())).findFirst().orElseThrow();
            assertEquals(WarCapabilityCatalog.State.IMPLEMENTED, entry.state()); assertEquals("1.21.11", entry.minimumMinecraft()); assertTrue(WarCapabilityCatalog.attack(kind).implemented());
        }
    }
    @Test void basicVanillaRangedWeaponsAndProvenancePreservingSwitchingAreImplemented() {
        for (var kind : List.of(WarAttackKind.BOW_SHOT, WarAttackKind.CROSSBOW_SHOT)) {
            var entry = WarCapabilityCatalog.entries().stream().filter(e -> e.id().equals(kind.key())).findFirst().orElseThrow();
            assertEquals(WarCapabilityCatalog.State.IMPLEMENTED, entry.state()); assertEquals("", entry.minimumMinecraft());
            assertEquals(AttackStatus.BLOCKED, WarNativeBindings.currentBuild().actors().attack(null, null, kind).status());
        }
        assertEquals(WarCapabilityCatalog.State.IMPLEMENTED, WarCapabilityCatalog.entries().stream().filter(e -> e.id().toString().equals("sagadyssey:war/capability/weapon_slot_switching")).findFirst().orElseThrow().state());
        assertTrue(WarCapabilityCatalog.attack(WarAttackKind.SWORD_STRIKE).implemented());
    }
    @Test void spearComponentsRequireTheMeleeContextRatherThanTheRangedInterface() {
        var nativePorts=WarNativeBindings.currentBuild();
        for(var kind:List.of(WarAttackKind.SPEAR_JAB,WarAttackKind.SPEAR_CHARGE,WarAttackKind.MOUNTED_SPEAR_CHARGE)){
            assertTrue(nativePorts.actors().support(kind).implemented());
            assertEquals(AttackStatus.BLOCKED,nativePorts.actors().rangedAttack(null,null,kind,null).status());
        }
    }
}
