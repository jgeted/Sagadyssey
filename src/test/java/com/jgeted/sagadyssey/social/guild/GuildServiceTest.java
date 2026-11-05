package com.jgeted.sagadyssey.social.guild;

import com.jgeted.sagadyssey.social.institution.InstitutionData;
import com.jgeted.sagadyssey.social.institution.InstitutionKind;
import com.jgeted.sagadyssey.social.institution.InstitutionMembership;
import com.jgeted.sagadyssey.social.institution.InstitutionService;
import com.jgeted.sagadyssey.social.institution.MembershipRank;
import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
import com.jgeted.sagadyssey.social.market.AppraisalData;
import com.jgeted.sagadyssey.social.market.AppraisalService;
import com.jgeted.sagadyssey.social.market.QualityGrade;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GuildServiceTest {
    @Test
    void qualifiedInspectorAppliesPublicStandardToUntamperedPhysicalItem() {
        InstitutionData institutions = new InstitutionData();
        GuildData guilds = new GuildData();
        AppraisalData appraisals = new AppraisalData();
        RightsStateData rights = new RightsStateData();
        UUID guildId = UUID.randomUUID();
        SubjectRef master = SubjectRef.character(UUID.randomUUID());
        SubjectRef inspector = SubjectRef.character(UUID.randomUUID());
        InstitutionService.establish(institutions, rights, guildId, "铁匠行会",
                InstitutionKind.CRAFT_GUILD, UUID.randomUUID(), UUID.randomUUID(), master,
                Set.of(OfficeAuthority.CERTIFY_CRAFT), 0L, "公开标准与师徒考核");
        assertTrue(institutions.addMembership(new InstitutionMembership(UUID.randomUUID(),
                guildId, inspector, MembershipRank.JOURNEYMAN, 70, 0L, true, "通过锻造考试")));
        CraftQualification qualification = GuildService.certify(institutions, guilds, guildId,
                master, inspector, "blacksmithing", CraftRank.JOURNEYMAN,
                List.of(UUID.randomUUID(), UUID.randomUUID()), 1L, 1000L, "两件实作证据");

        UUID bagId = UUID.randomUUID();
        SimpleContainer bag = new SimpleContainer(2);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        bag.setItem(0, sword);
        rights.ensureContext(bagId, "inspector_sample", inspector, inspector, AccessPolicy.PRIVATE);
        GuildQualityStandard standard = GuildService.adoptStandard(institutions, guilds,
                guildId, master, "blacksmithing", ItemFingerprint.of(sword),
                Set.of(QualityGrade.NEW_OR_FINE), 2L, "新制铁剑不得有明显损耗");
        var appraisal = AppraisalService.appraise(appraisals, rights, inspector, inspector,
                bagId, bag, 0, 3L, 100L);

        GuildInspection inspection = GuildService.inspect(guilds, appraisals, standard.id(),
                inspector, appraisal.markId(), bagId, bag, 0, 4L);
        assertTrue(inspection.passed());
        assertEquals(qualification.id(), inspection.qualificationId());

        sword.setDamageValue(80);
        assertThrows(IllegalStateException.class, () -> GuildService.inspect(guilds,
                appraisals, standard.id(), inspector, appraisal.markId(), bagId, bag, 0, 5L));
    }

    @Test
    void officeCannotCertifyACharacterWhoNeverJoinedTheGuild() {
        InstitutionData institutions = new InstitutionData();
        GuildData guilds = new GuildData();
        RightsStateData rights = new RightsStateData();
        UUID guildId = UUID.randomUUID();
        SubjectRef master = SubjectRef.character(UUID.randomUUID());
        InstitutionService.establish(institutions, rights, guildId, "木匠行会",
                InstitutionKind.CRAFT_GUILD, UUID.randomUUID(), UUID.randomUUID(), master,
                Set.of(OfficeAuthority.CERTIFY_CRAFT), 0L, "章程");

        assertThrows(IllegalStateException.class, () -> GuildService.certify(institutions,
                guilds, guildId, master, SubjectRef.character(UUID.randomUUID()),
                "carpentry", CraftRank.APPRENTICE, List.of(UUID.randomUUID()),
                1L, 100L, "无成员身份"));
    }
}
