package com.jgeted.sagadyssey.social.combat;

/** Converts morale, injuries, local odds, and orders into an explainable survival decision. */
public final class CombatDecisionService {
    private CombatDecisionService() { }

    public static CombatDecision evaluate(int morale, TriageAssessment triage,
                                          int allies, int enemies, boolean escapeRoute,
                                          boolean surrenderAllowed, boolean orderedHold,
                                          boolean defendingDependants) {
        if (triage.incapacitated()) {
            return new CombatDecision(CombatPosture.INCAPACITATED, 0, "伤势已使其失去战斗能力");
        }
        int safeAllies = Math.max(1, allies);
        int oddsPenalty = Math.max(0, enemies - safeAllies) * 7;
        int injuryPenalty = triage.totalTrauma() / 4 + triage.bleeding() / 3
                + triage.mobilityPenalty() / 5;
        int resolve = Math.max(0, Math.min(100, morale - oddsPenalty - injuryPenalty
                + (orderedHold ? 10 : 0) + (defendingDependants ? 8 : 0)));
        if (resolve <= 10) {
            if (escapeRoute) return new CombatDecision(CombatPosture.ROUT, resolve,
                    "伤势、敌我数量与士气共同导致溃逃");
            if (surrenderAllowed) return new CombatDecision(CombatPosture.SURRENDER, resolve,
                    "无安全退路且继续战斗缺乏胜算");
            return new CombatDecision(CombatPosture.WITHDRAW, resolve, "尝试脱离无法维持的接触");
        }
        if (resolve <= 32) return new CombatDecision(CombatPosture.WITHDRAW, resolve,
                orderedHold ? "即使接到坚守命令，生存压力仍占上风" : "保存生命并寻找更安全位置");
        if (resolve >= 72 && enemies <= safeAllies + 1 && triage.totalTrauma() < 35) {
            return new CombatDecision(CombatPosture.ADVANCE, resolve, "士气、兵力和伤势允许推进");
        }
        return new CombatDecision(CombatPosture.HOLD, resolve, "仍可维持阵位但不宜冒进");
    }
}
