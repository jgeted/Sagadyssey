package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.npc.faction.Faction;
import com.jgeted.sagadyssey.npc.faction.FactionStandings;
import com.jgeted.sagadyssey.npc.faction.StandingLevel;

/** One authoritative bridge between faction standing and the physical-goods trade flow. */
public final class NpcTradeAccessPolicy {
    private NpcTradeAccessPolicy() { }

    public static Decision evaluate(FactionStandings standings, Faction faction,
                                    boolean directServiceParty) {
        if (directServiceParty || faction == null || "sagadyssey:player".equals(faction.id())) {
            return new Decision(true, 10_000, directServiceParty
                    ? "你们已有直接服务关系，按正常条件核价"
                    : "按正常条件核价");
        }
        StandingLevel level = standings.getLevel(faction);
        if (!level.canTrade()) {
            return new Decision(false, level.tradePriceBasisPoints(),
                    "对方所属势力目前拒绝与你贸易");
        }
        int basisPoints = level.tradePriceBasisPoints();
        return new Decision(true, basisPoints, basisPoints == 10_000
                ? "按正常条件核价"
                : "声望使对方的报价门槛为正常条件的 " + basisPoints / 100 + "%");
    }

    public record Decision(boolean allowed, int priceBasisPoints, String explanation) { }
}
