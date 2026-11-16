package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarKitCodec;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarKitRecord;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarMobilizationCodec;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarMusterCodec;
import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.api.WarLoadout;
import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import com.jgeted.sagadyssey.social.war.application.WarMobilizationService;
import com.jgeted.sagadyssey.social.war.application.WarMusterBook;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.*;
import com.jgeted.sagadyssey.social.war.domain.WarTransmission;
import com.jgeted.sagadyssey.social.work.ServiceProvision;
import com.jgeted.sagadyssey.social.work.ServiceProvisionCodec;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WarCampaignCapacityTest {
    private static final WarKey PROFILE = new WarLoadout(WarLoadout.Mount.FOOT, WarLoadout.Weapon.SWORD, false, false).id();
    private static final WarKey CURRENCY = new WarKey("minecraft", "emerald");

    private static Army army(int number) {
        UUID owner = new UUID(0, number + 1);
        return new Army(WarMusterEntry.armyId(owner, "Campaign"), owner, "Campaign", 10);
    }

    private static Unit unit(Army army, int number) {
        String name = "Company" + number;
        return new Unit(WarMusterEntry.unitId(army.id(), name), army.id(), name, PROFILE, 1, false, 1);
    }

    private static Offer offer(Army army, Unit unit, int number) {
        var request = new WarMilitiaRequest(new UUID(1, number), army.owner(), army.id(), unit.id(), new UUID(2, number),
                PROFILE, 1, WarMilitiaRequest.Kind.MILITIA, WarMilitiaRequest.Role.COMBATANT,
                new WarMilitiaRequest.Wage(CURRENCY, 1, 24_000), 20);
        return new Offer(request, new UUID(3, number), new UUID(4, number), 1, "minecraft:overworld", null,
                48_020, 0.5, 0.75, Stage.PROPOSED, 1, 20, "capacity fixture");
    }

    @Test void everyCampaignLedgerRetainsTheSameTwelveHundredIds() throws Exception {
        var journal = new WarMusterBookTest.Journal();
        var book = new WarMusterBook(journal);
        var mobilizations = new ArrayList<WarMobilization>();
        var provisions = new ArrayList<ServiceProvision>();
        var loadouts = new ArrayList<WarKitRecord>();
        int person = 0;
        for (int side = 0; side < 3; side++) {
            var army = army(side);
            assertTrue(book.put(army, 10).durable().isPresent());
            var treasury = ItemLocation.player(army.owner(), new UUID(5, side));
            int companies = side == 1 ? 12 : 6;
            for (int company = 0; company < companies; company++) {
                var unit = unit(army, company);
                assertTrue(book.put(unit, 10).durable().isPresent());
                assertTrue(book.put(unit.acknowledged(), 10).durable().isPresent());
                for (int i = 0; i < 50; i++) {
                    var offer = offer(army, unit, person++);
                    assertTrue(book.put(offer, 20).durable().isPresent());
                    var request = offer.request();
                    mobilizations.add(WarMobilization.start(request));
                    provisions.add(ServiceProvision.configured(new ServiceProvision.Terms(request.id(), "sagadyssey:war", army.id(),
                            request.characterId(), offer.body(), SubjectRef.character(army.owner()), treasury,
                            new ItemFingerprint(CURRENCY.toString(), 0), 1, 24_000), 20));
                    loadouts.add(new WarKitRecord(request, offer.body(), treasury, List.of(), List.of(), List.of()));
                }
            }
        }
        assertEquals(1_200, person);
        var musterCodec = new WarMusterCodec();
        var mobCodec = new WarMobilizationCodec(16 * 1024 * 1024, WarMobilizationService.RECORD_LIMIT);
        var provisionCodec = new ServiceProvisionCodec();
        var kitCodec = new WarKitCodec();
        assertEquals(new HashSet<>(book.entries()), new HashSet<>(musterCodec.read(musterCodec.write(book.entries()))));
        assertEquals(new HashSet<>(mobilizations), new HashSet<>(mobCodec.read(mobCodec.write(mobilizations))));
        assertEquals(new HashSet<>(provisions), new HashSet<>(provisionCodec.read(provisionCodec.write(provisions))));
        assertEquals(new HashSet<>(loadouts), new HashSet<>(kitCodec.read(kitCodec.write(loadouts))));
    }

    @Test void fullCompanyRejectsBeforeAcceptanceAndReopensWhenADeclinedOfferReleasesItsSeat() {
        var journal = new WarMusterBookTest.Journal();
        var book = new WarMusterBook(journal);
        var army = army(0);
        var unit = unit(army, 0);
        book.put(army, 10);
        book.put(unit, 10);
        book.put(unit.acknowledged(), 10);
        for (int i = 0; i < WarTransmission.MAX_MEMBERS; i++) assertTrue(book.put(offer(army, unit, i), 20).durable().isPresent());
        var next = offer(army, unit, WarTransmission.MAX_MEMBERS);
        var reopened = new WarMusterBook(journal);
        assertEquals("UNIT_COMBATANT_CAPACITY", reopened.offerAdmission(next.request(), 20));
        assertEquals("UNIT_COMBATANT_CAPACITY", reopened.put(next, 20).reason());
        assertTrue(reopened.problem().isEmpty());
        reopened.put(offer(army, unit, 0).advance(Stage.DECLINED, 21, "npc refused"), 21);
        assertTrue(reopened.put(next, 21).durable().isPresent());
        assertEquals(Stage.DECLINED, reopened.offer(new UUID(1, 0)).orElseThrow().stage());
    }
}
