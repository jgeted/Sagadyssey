package com.jgeted.sagadyssey.social.work;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import com.jgeted.sagadyssey.social.life.NpcWorkAuthority;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.LogisticsOrderRecord;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * NPC 当前唯一生效的工作身份。普通招募与机构岗位互斥；只有显式 CONTRACT
 * 才能在玩家指挥下继续使用指定机构设施。
 */
public record NpcWorkContext(NpcWorkAuthority authority,
                             @Nullable UUID employerCharacterId,
                             @Nullable UUID workplaceSettlementId) {

    public static NpcWorkContext from(NpcBase npc) {
        NpcLifeData data = NpcLifeData.from(npc);
        NpcLifeRecord life = data == null ? null : data.record(npc.getCharacterId());
        if (life != null) {
            UUID employer = life.authority() == NpcWorkAuthority.SERVICE
                    || life.authority() == NpcWorkAuthority.CONTRACT ? life.authorityId() : null;
            UUID workplace = life.authority() == NpcWorkAuthority.INSTITUTION
                    || life.authority() == NpcWorkAuthority.CONTRACT
                    ? life.activeWorkplaceSettlementId() : null;
            return new NpcWorkContext(life.authority(), employer, workplace);
        }
        // 旧存档兼容规则也保持互斥：玩家主人优先，出生聚落不能同时授予工作权限。
        if (npc.getOwnerCharacterId() != null) {
            return new NpcWorkContext(NpcWorkAuthority.SERVICE, npc.getOwnerCharacterId(), null);
        }
        if (npc.getHomeSettlementId() != null) {
            return new NpcWorkContext(NpcWorkAuthority.INSTITUTION, null, npc.getHomeSettlementId());
        }
        return new NpcWorkContext(NpcWorkAuthority.NONE, null, null);
    }

    public boolean hasEmployer() { return employerCharacterId != null; }
    public boolean hasWorkplace() { return workplaceSettlementId != null; }
    public boolean hasEmployment() { return hasEmployer() || hasWorkplace(); }

    /**
     * 权限只从当前工作权威推导，不从籍贯或私人住宅推导。
     */
    public boolean canUseWarehouse(MinecraftServer server, WarehouseRecord warehouse) {
        if ((authority == NpcWorkAuthority.INSTITUTION || authority == NpcWorkAuthority.CONTRACT)
                && workplaceSettlementId != null
                && workplaceSettlementId.equals(warehouse.settlementId())) return true;
        if ((authority != NpcWorkAuthority.SERVICE && authority != NpcWorkAuthority.CONTRACT)
                || employerCharacterId == null) return false;
        if (employerCharacterId.equals(warehouse.registeredByCharacter())) return true;

        SettlementRecord settlement = SettlementRegistryData.get(server).byId(warehouse.settlementId());
        if (settlement == null) return false;
        if (employerCharacterId.equals(settlement.founderCharacterId())) return true;
        return PoliticalStateData.get(server).canGrantTitle(
                settlement.factionId(), settlement.id(), employerCharacterId);
    }

    /** 订单与当前唯一权威匹配才可领取。 */
    public boolean canClaimOrder(LogisticsData logistics, LogisticsOrderRecord order) {
        if (employerCharacterId != null
                && employerCharacterId.equals(order.createdByCharacter())) return true;
        if (workplaceSettlementId == null) return false;
        WarehouseRecord source = logistics.warehouse(order.sourceWarehouseId());
        WarehouseRecord destination = logistics.warehouse(order.destinationWarehouseId());
        return source != null && destination != null
                && (workplaceSettlementId.equals(source.settlementId())
                || workplaceSettlementId.equals(destination.settlementId()));
    }

    public String describe() {
        return switch (authority) {
            case SERVICE, PLAYER -> "有限服务关系（原机构岗位暂停）";
            case INSTITUTION -> "聚落机构岗位";
            case CONTRACT -> "显式外派合同";
            case NONE -> "自由职业（仅使用随身物资）";
        };
    }
}
