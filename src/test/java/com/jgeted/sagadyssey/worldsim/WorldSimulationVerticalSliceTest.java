package com.jgeted.sagadyssey.worldsim;

import com.jgeted.sagadyssey.npc.trade.TradeAsset;
import com.jgeted.sagadyssey.npc.trade.TradeCatalogService;
import com.jgeted.sagadyssey.npc.trade.TradeIntent;
import com.jgeted.sagadyssey.npc.trade.TradeProposal;
import com.jgeted.sagadyssey.npc.trade.TradeSession;
import com.jgeted.sagadyssey.npc.trade.TradeSessionData;
import com.jgeted.sagadyssey.npc.trade.TradeSessionStatus;
import com.jgeted.sagadyssey.npc.trade.TradeSettlementService;
import com.jgeted.sagadyssey.social.combat.CombatEncounter;
import com.jgeted.sagadyssey.social.combat.CombatEncounterData;
import com.jgeted.sagadyssey.social.combat.CombatEncounterService;
import com.jgeted.sagadyssey.social.combat.CombatMoraleData;
import com.jgeted.sagadyssey.social.combat.CombatSide;
import com.jgeted.sagadyssey.social.combat.EncounterEvent;
import com.jgeted.sagadyssey.social.combat.EncounterEventType;
import com.jgeted.sagadyssey.social.combat.EngagementAction;
import com.jgeted.sagadyssey.social.combat.EngagementRule;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.combat.InjuryService;
import com.jgeted.sagadyssey.social.combat.MedicalService;
import com.jgeted.sagadyssey.social.combat.TreatmentType;
import com.jgeted.sagadyssey.social.combat.WoundRecord;
import com.jgeted.sagadyssey.social.combat.WoundType;
import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.InstitutionKind;
import com.jgeted.sagadyssey.social.institution.InstitutionMembership;
import com.jgeted.sagadyssey.social.institution.InstitutionService;
import com.jgeted.sagadyssey.social.institution.MembershipRank;
import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
import com.jgeted.sagadyssey.social.justice.EvidenceKind;
import com.jgeted.sagadyssey.social.justice.EvidenceRef;
import com.jgeted.sagadyssey.social.security.IncidentStatus;
import com.jgeted.sagadyssey.social.security.IncidentType;
import com.jgeted.sagadyssey.social.security.PatrolDispatchStatus;
import com.jgeted.sagadyssey.social.security.SecurityData;
import com.jgeted.sagadyssey.social.security.SecurityService;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ExpectedRevision;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import com.jgeted.sagadyssey.worldsim.transfer.TransferReceipt;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One causal chain across system boundaries: lawful trade, physical theft, institutional response,
 * bounded force, persistent injury, and treatment with consumed supplies.
 */
class WorldSimulationVerticalSliceTest {
    @Test
    void marketJourneyBanditAttackAndRecoveryShareAuthoritativeWorldFacts() {
        UUID traveler = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        UUID raider = UUID.randomUUID();
        UUID captainId = UUID.randomUUID();
        UUID guardA = UUID.randomUUID();
        UUID guardB = UUID.randomUUID();
        UUID travelerBagId = UUID.randomUUID();
        UUID merchantBagId = UUID.randomUUID();
        UUID raiderBagId = UUID.randomUUID();
        UUID medicBagId = UUID.randomUUID();
        SimpleContainer travelerBag = new SimpleContainer(6);
        SimpleContainer merchantBag = new SimpleContainer(6);
        SimpleContainer raiderBag = new SimpleContainer(6);
        SimpleContainer medicBag = new SimpleContainer(2);
        travelerBag.setItem(0, new ItemStack(Items.EMERALD, 2));
        merchantBag.setItem(0, new ItemStack(Items.BREAD, 6));
        medicBag.setItem(0, new ItemStack(Items.WHITE_WOOL));

        RightsStateData rights = new RightsStateData();
        registerPrivate(rights, travelerBagId, traveler);
        registerPrivate(rights, merchantBagId, merchant);
        registerPrivate(rights, raiderBagId, raider);
        registerPrivate(rights, medicBagId, captainId);
        TransferLedgerData transfers = new TransferLedgerData();
        Map<UUID, TransferEndpoint> tradeEndpoints = Map.of(
                travelerBagId, new TransferEndpoint(travelerBagId, travelerBag),
                merchantBagId, new TransferEndpoint(merchantBagId, merchantBag));

        // A real spot trade changes possession and title together; emerald is an item, not a
        // synthetic money balance.
        TradeSession open = TradeSession.open(traveler, merchant, travelerBagId,
                merchantBagId, 10L, 200L);
        TradeProposal proposal = new TradeProposal(UUID.randomUUID(), open.id(), 1, traveler,
                TradeIntent.SPOT_PURCHASE,
                List.of(asset(Items.EMERALD, 2, "绿宝石")),
                List.of(asset(Items.BREAD, 4, "面包")), 11L, 190L);
        TradeSession accepted = open.withProposal(proposal, TradeSessionStatus.ACCEPTED,
                "双方当面同意");
        TransferReceipt sale = TradeSettlementService.commit(accepted, tradeEndpoints,
                rights, transfers, 12L);

        assertTrue(sale.successful());
        assertTrue(sale.lawful());
        assertEquals(4, count(travelerBag, Items.BREAD));
        assertEquals(2, count(merchantBag, Items.BREAD));
        assertEquals(2, count(merchantBag, Items.EMERALD));

        // The attack moves physical bread but deliberately keeps lawful title with the victim.
        UUID robberyId = UUID.randomUUID();
        ItemFingerprint bread = ItemFingerprint.of(new ItemStack(Items.BREAD));
        TransferRequest robbery = new TransferRequest(robberyId, SubjectRef.character(raider),
                TransferIntent.THEFT,
                List.of(new TransferLeg(travelerBagId, raiderBagId, bread, 2)),
                List.of(revision(rights, travelerBagId), revision(rights, raiderBagId)),
                "encounter:forest-road", 20L);
        TransferReceipt stolen = new TransferService(rights, transfers).submit(robbery,
                Map.of(travelerBagId, new TransferEndpoint(travelerBagId, travelerBag),
                        raiderBagId, new TransferEndpoint(raiderBagId, raiderBag)), 21L);

        assertTrue(stolen.successful());
        assertFalse(stolen.lawful());
        assertEquals(2, count(travelerBag, Items.BREAD));
        assertEquals(2, count(raiderBag, Items.BREAD));
        assertTrue(TradeCatalogService.visibleNpcOffers(raider, raiderBagId, raiderBag,
                rights, new TradeSessionData(), null, 22L).isEmpty(),
                "stolen goods cannot become lawful stock merely by entering another inventory");

        // A public institution may act only through explicit offices and actual members.
        UUID settlementId = UUID.randomUUID();
        UUID militiaId = UUID.randomUUID();
        SubjectRef captain = SubjectRef.character(captainId);
        SubjectRef guardARef = SubjectRef.character(guardA);
        SubjectRef guardBRef = SubjectRef.character(guardB);
        InstitutionData institutions = new InstitutionData();
        InstitutionService.establish(institutions, rights, militiaId, "河桥守备队",
                InstitutionKind.MILITIA, settlementId, UUID.randomUUID(), captain,
                Set.of(OfficeAuthority.INVESTIGATE, OfficeAuthority.COMMAND_PATROL),
                0L, "保护道路；强制行动必须有可核验证据");
        institutions.addMembership(new InstitutionMembership(UUID.randomUUID(), militiaId,
                guardARef, MembershipRank.MEMBER, 70, 0L, true, "巡路守卫"));
        institutions.addMembership(new InstitutionMembership(UUID.randomUUID(), militiaId,
                guardBRef, MembershipRank.MEMBER, 65, 0L, true, "巡路守卫"));

        SecurityData security = new SecurityData();
        InjuryData injuries = new InjuryData();
        UUID incidentId = UUID.randomUUID();
        SecurityService.report(security, incidentId, settlementId,
                IncidentType.BANDIT_ATTACK, "minecraft:overworld", new BlockPos(96, 64, 24),
                SubjectRef.character(traveler), SubjectRef.character(raider), 82,
                SubjectRef.character(traveler), 25L, "林道运粮者遭袭");
        SecurityService.addEvidence(security, incidentId, new EvidenceRef(UUID.randomUUID(),
                EvidenceKind.TRANSFER_RECEIPT, robberyId, SubjectRef.character(traveler),
                26L, "现场损失与实体转移账相符"));
        assertEquals(IncidentStatus.VERIFIED, SecurityService.verify(security, institutions,
                transfers, injuries, incidentId, militiaId, captain, 27L).status());
        assertEquals(PatrolDispatchStatus.EN_ROUTE,
                SecurityService.dispatch(security, institutions, incidentId, militiaId,
                        captain, List.of(guardARef, guardBRef), 28L, 200L,
                        "保护受害者，优先逮捕嫌疑人").status());

        // The same dispatch becomes a combat side with arrest rules, not blanket kill authority.
        UUID encounterId = UUID.randomUUID();
        CombatEncounterData encounters = new CombatEncounterData();
        CombatMoraleData morale = new CombatMoraleData();
        CombatEncounter encounter = CombatEncounterService.start(encounters, encounterId,
                settlementId, "minecraft:overworld", new BlockPos(96, 64, 24), 24,
                List.of(new CombatSide(UUID.randomUUID(), "河桥守备队", militiaId,
                                Set.of(guardA, guardB), EngagementRule.ARREST_IF_POSSIBLE),
                        new CombatSide(UUID.randomUUID(), "林道劫匪", null,
                                Set.of(raider), EngagementRule.LETHAL_FORCE)), 30L);
        assertEquals(EngagementAction.ARREST,
                CombatEncounterService.engagement(encounter, guardA, raider, true).action());
        assertEquals(EngagementAction.IGNORE,
                CombatEncounterService.engagement(encounter, guardA, guardB, true).action());

        UUID woundId = UUID.randomUUID();
        WoundRecord wound = InjuryService.recordHit(injuries, woundId, guardA, raider,
                WoundType.CUT, 7.0F, 0.25D, 35L, robberyId);
        CombatEncounterService.record(encounters, morale, encounterId,
                new EncounterEvent(UUID.randomUUID(), encounterId, EncounterEventType.WOUNDED,
                        guardA, raider, woundId, 35L, "逮捕过程中守卫负伤"));
        assertTrue(morale.profile(guardB, 35L).current() < 50,
                "an observed ally wound must affect only that combat side's morale");

        SecurityService.addEvidence(security, incidentId, new EvidenceRef(UUID.randomUUID(),
                EvidenceKind.INJURY_RECORD, woundId, captain, 36L,
                "处置行动形成的伤情记录"));
        int bleedingBeforeTreatment = wound.bleeding();
        MedicalService.treat(injuries, rights, UUID.randomUUID(), woundId, captain,
                medicBagId, medicBag, TreatmentType.BANDAGE, 40L);

        assertEquals(0, count(medicBag, Items.WHITE_WOOL));
        assertTrue(injuries.wound(woundId).bleeding() < bleedingBeforeTreatment);
        assertEquals(IncidentStatus.RESPONDING, security.incident(incidentId).status());
        assertNotNull(transfers.receipt(robberyId));
        assertEquals(6, count(merchantBag, Items.BREAD) + count(travelerBag, Items.BREAD)
                + count(raiderBag, Items.BREAD));
        assertEquals(2, count(merchantBag, Items.EMERALD) + count(travelerBag, Items.EMERALD));
    }

    private static TradeAsset asset(Item item, int quantity, String label) {
        return new TradeAsset(ItemFingerprint.of(new ItemStack(item)), quantity, label);
    }

    private static ExpectedRevision revision(RightsStateData rights, UUID containerId) {
        return new ExpectedRevision(containerId, rights.context(containerId).revision());
    }

    private static void registerPrivate(RightsStateData rights, UUID containerId, UUID owner) {
        SubjectRef character = SubjectRef.character(owner);
        rights.ensureContext(containerId, "test", character, character, AccessPolicy.PRIVATE);
    }

    private static int count(SimpleContainer container, Item item) {
        int result = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) result += container.getItem(slot).getCount();
        }
        return result;
    }
}
