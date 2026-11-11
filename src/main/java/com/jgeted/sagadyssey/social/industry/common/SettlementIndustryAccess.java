package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.core.structure.NaturalFarmRules;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcWorkAuthority;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;

import java.util.Objects;

/** Normal-world eligibility is separate from the test fixtures and confers no asset rights. */
public final class SettlementIndustryAccess {
    private SettlementIndustryAccess() {
    }

    public static boolean fixture(NpcBase npc, String property) {
        return MinecraftApi.server(npc) instanceof GameTestServer && Boolean.getBoolean(property);
    }

    public static boolean resident(NpcBase npc) {
        var server = MinecraftApi.server(npc);
        if (server == null || !SagadysseyConfig.ENABLE_SETTLEMENT_INDUSTRIES.get()
                || !npc.isAlive() || npc.getCharacterId() == null || !NpcBrainRuntime.isPlanned(npc)) {
            return false;
        }
        var village = NaturalFarmRules.forResident(npc);
        if (village == null || !SettlementCoordinatorData.active(server, village.buildingKey())
                || !village.dimension().equals(npc.level().dimension().identifier().toString())
                || !npc.level().hasChunkAt(NaturalVillageLayout.at(village, NaturalVillageLayout.PUBLIC))
                || npc.blockPosition().distSqr(village.buildingOrigin()) > 64 * 64) {
            return false;
        }
        var home = HouseholdData.get(server).forMember(npc.getCharacterId());
        var life = NpcLifeData.get(server).record(npc.getCharacterId());
        return home != null && Objects.equals(home.settlementId(), village.settlementId())
                && life != null && life.authority() == NpcWorkAuthority.INSTITUTION
                && Objects.equals(life.activeWorkplaceSettlementId(), village.settlementId())
                && !NaturalPolityData.paused(server, npc.getCharacterId());
    }

    public static boolean local(NpcBase npc, BlockPos position) {
        var village = NaturalFarmRules.forResident(npc);
        return village != null && village.dimension().equals(npc.level().dimension().identifier().toString())
                && position.distSqr(village.buildingOrigin()) <= 64 * 64
                && npc.level().hasChunkAt(position);
    }

    public static boolean servesLocalPrincipal(NpcBase npc, SubjectRef subject) {
        var village = NaturalFarmRules.forResident(npc);
        if (village == null) return false;
        var households = HouseholdData.get(MinecraftApi.server(npc));
        return switch (subject.type()) {
            case CHARACTER -> {
                var home = households.forMember(subject.id());
                yield home != null && village.settlementId().equals(home.settlementId());
            }
            case HOUSEHOLD -> {
                var home = households.byId(subject.id());
                yield home != null && village.settlementId().equals(home.settlementId());
            }
            case INSTITUTION -> village.settlementId().equals(subject.id());
            default -> false;
        };
    }
}
