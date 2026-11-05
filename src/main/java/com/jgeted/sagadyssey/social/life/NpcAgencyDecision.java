package com.jgeted.sagadyssey.social.life;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 共用的有界理性决策器。它使用 NPC 的主观档案、边际递减、概率选择和决策锁，
 * 不把任何一个精确分数直接变成百分之百确定的行为。
 */
public final class NpcAgencyDecision {
    public enum Action {
        WORK("履行工作"), PATROL("执行巡逻"), PROTECT("保护亲友"),
        REST("回家休息"), EAT("寻找食物"), CARE("照料家庭"), RETREAT("撤离战斗");
        private final String displayName;
        Action(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    private NpcAgencyDecision() { }

    public static boolean choose(NpcBase npc, Action action, double situationBenefit,
                                 double risk, boolean urgent) {
        return choose(npc, action, null, situationBenefit, risk, urgent);
    }

    public static boolean choose(NpcBase npc, Action action, @Nullable UUID subjectCharacterId,
                                 double situationBenefit, double risk, boolean urgent) {
        if (!(npc.level() instanceof ServerLevel level)) return true;
        NpcLifeData data = NpcLifeData.get(level.getServer());
        NpcLifeRecord life = data.record(npc.getCharacterId());
        if (life == null) return true;
        long now = level.getGameTime();
        NpcActionDecisionRecord commitment = data.actionDecision(npc.getCharacterId(), action.name());
        if (!urgent && commitment != null && now < commitment.lockedUntil()
                && commitment.sameSubject(subjectCharacterId)) {
            return commitment.accepted();
        }
        String accept = action.displayName();
        String decline = "暂缓" + accept;

        double need = needBenefit(npc, life, data, action);
        double personality = personalityBenefit(life, action);
        NpcEmploymentContract contract = data.contract(npc.getCharacterId());
        double obligation = obligationBenefit(life, contract, action);
        double goal = goalBenefit(life, action);
        double relationships = relationshipBenefit(life, action, subjectCharacterId);
        double values = valuesBenefit(npc, life, action);
        double memory = memoryBenefit(life, action, subjectCharacterId, now);
        double opportunityCost = action == Action.REST || action == Action.RETREAT || action == Action.EAT
                ? Math.sqrt(life.duty()) * 1.1D : 18.0D * Math.log1p(life.fatigue() / 180.0D);
        double utility = situationBenefit + need + personality + obligation + goal
                + relationships + values + memory - Math.max(0.0D, risk) - opportunityCost;
        double probability = 1.0D / (1.0D + Math.exp(-(utility - 52.0D) / 12.0D));
        probability = Math.max(0.04D, Math.min(0.97D, probability));
        boolean chosen = npc.getRandom().nextDouble() < probability;
        long lock = now + (chosen ? 180L + npc.getRandom().nextInt(221)
                : 60L + npc.getRandom().nextInt(101));
        String reason = "需求=" + round(need) + "，性格=" + round(personality)
                + "，义务=" + round(obligation) + "，关系=" + round(relationships)
                + "，道德与信仰=" + round(values) + "，长期目标=" + round(goal)
                + "，记忆=" + round(memory)
                + "，风险=" + round(risk) + "，机会成本=" + round(opportunityCost)
                + "，本次概率=" + Math.round(probability * 100.0D) + "%";
        data.updateDecision(npc.getCharacterId(), chosen ? accept : decline, reason,
                now, lock, life.fatigue());
        data.updateActionDecision(new NpcActionDecisionRecord(npc.getCharacterId(), action.name(),
                chosen, subjectCharacterId, now, lock, reason));
        return chosen;
    }

    private static double needBenefit(NpcBase npc, NpcLifeRecord life,
                                      NpcLifeData data, Action action) {
        return switch (action) {
            case REST -> 28.0D * Math.log1p(life.fatigue() / 90.0D);
            case EAT -> {
                NpcNeedsRecord needs = data.needs(npc.getCharacterId());
                yield needs == null ? 0.0D : 34.0D * Math.log1p(needs.hunger() / 120.0D);
            }
            case CARE -> 5.0D;
            case RETREAT -> (100.0D - npc.getMoral()) * 0.32D
                    + 13.0D * Math.log1p(life.fatigue() / 180.0D);
            case WORK -> 8.0D + Math.sqrt(life.ambition()) * 0.8D;
            case PATROL -> 5.0D;
            case PROTECT -> 8.0D;
        };
    }

    private static double personalityBenefit(NpcLifeRecord life, Action action) {
        return switch (action) {
            case WORK -> Math.sqrt(life.duty()) * 1.5D + Math.sqrt(life.ambition()) * 0.7D;
            case PATROL -> Math.sqrt(life.duty()) * 1.2D + (life.courage() - 50.0D) * 0.20D;
            case PROTECT -> Math.sqrt(life.duty()) * 1.1D + (life.courage() - 50.0D) * 0.24D;
            case REST -> (50.0D - life.duty()) * 0.08D;
            case EAT -> 0.0D;
            case CARE -> Math.sqrt(life.duty()) * 1.1D
                    + Math.sqrt(life.sociability()) * 0.7D;
            case RETREAT -> (50.0D - life.courage()) * 0.30D;
        };
    }

    private static double obligationBenefit(NpcLifeRecord life, NpcEmploymentContract contract,
                                            Action action) {
        if (action == Action.PROTECT) return 8.0D;
        if (action == Action.EAT || action == Action.CARE) return 0.0D;
        if (action != Action.WORK && action != Action.PATROL) return 0.0D;
        double base = switch (life.authority()) {
            case INSTITUTION -> 15.0D;
            case SERVICE, PLAYER -> 18.0D;
            case CONTRACT -> 22.0D;
            case NONE -> 0.0D;
        };
        if (life.authority() != NpcWorkAuthority.CONTRACT || contract == null) return base;
        double employerTrust = life.relations().stream()
                .filter(relation -> relation.otherCharacterId().equals(contract.employerCharacterId()))
                .mapToInt(NpcRelationRecord::strength).findFirst().orElse(0) * 0.12D;
        if (contract.status() == NpcContractStatus.IN_ARREARS) {
            double wagePressure = (contract.unpaidEmeralds() / (double) contract.wageEmeralds()) * 7.0D;
            return base + employerTrust
                    - Math.min(30.0D, wagePressure + contract.missedPayments() * 4.0D);
        }
        if (contract.status() == NpcContractStatus.PAYMENT_BLOCKED) return base + employerTrust - 2.0D;
        return base + employerTrust;
    }

    private static double goalBenefit(NpcLifeRecord life, Action action) {
        return switch (life.longTermGoal()) {
            case SURVIVE -> action == Action.REST || action == Action.EAT
                    || action == Action.RETREAT ? 12.0D : 2.0D;
            case PROTECT_FAMILY -> action == Action.PROTECT ? 28.0D
                    : action == Action.CARE ? 24.0D : action == Action.PATROL ? 20.0D
                    : action == Action.RETREAT ? -8.0D : 2.0D;
            case PROSPER -> action == Action.WORK ? 18.0D : 0.0D;
            case MASTER_CRAFT -> action == Action.WORK ? 22.0D : 0.0D;
            case DEFEND_HOME -> action == Action.PROTECT ? 14.0D
                    : action == Action.PATROL ? 22.0D : action == Action.RETREAT ? -10.0D : 0.0D;
            case SERVE_FAITH -> action == Action.WORK || action == Action.PATROL ? 10.0D : 0.0D;
            case GAIN_STATUS -> action == Action.WORK || action == Action.PATROL ? 14.0D : 0.0D;
            case SEEK_FREEDOM -> action == Action.WORK && life.authority() != NpcWorkAuthority.NONE ? -16.0D : 5.0D;
        };
    }

    private static double relationshipBenefit(NpcLifeRecord life, Action action,
                                              @Nullable UUID subjectCharacterId) {
        if (subjectCharacterId == null) return 0.0D;
        int strength = life.relations().stream()
                .filter(relation -> relation.otherCharacterId().equals(subjectCharacterId))
                .mapToInt(NpcRelationRecord::strength).findFirst().orElse(0);
        return switch (action) {
            case PATROL -> strength * 0.10D;
            case PROTECT -> strength * 0.28D;
            case RETREAT -> life.longTermGoal() == NpcLongTermGoal.PROTECT_FAMILY
                    ? -Math.max(0.0D, strength) * 0.14D : 0.0D;
            case WORK -> strength * 0.08D;
            case REST -> 0.0D;
            case EAT -> 0.0D;
            case CARE -> strength * 0.24D;
        };
    }

    private static double valuesBenefit(NpcBase npc, NpcLifeRecord life, Action action) {
        int conscience = life.conscience();
        int faith = life.faith();
        return switch (action) {
            case CARE -> (conscience - 50.0D) * 0.22D + (faith - 50.0D) * 0.08D;
            case PROTECT -> (conscience - 50.0D) * 0.18D;
            case RETREAT -> life.longTermGoal() == NpcLongTermGoal.SERVE_FAITH
                    ? -(faith - 50.0D) * 0.08D : 0.0D;
            case WORK, PATROL -> life.longTermGoal() == NpcLongTermGoal.SERVE_FAITH
                    ? (faith - 50.0D) * 0.12D : 0.0D;
            case REST, EAT -> 0.0D;
        };
    }

    private static double memoryBenefit(NpcLifeRecord life, Action action,
                                        @Nullable UUID subjectCharacterId, long gameTime) {
        double value = 0.0D;
        for (NpcMemoryRecord memory : life.memories()) {
            if (!memory.active(gameTime)) continue;
            if (subjectCharacterId != null && memory.subjectCharacterId() != null
                    && !subjectCharacterId.equals(memory.subjectCharacterId())) continue;
            double weight = memory.intensity() / 100.0D;
            if (action == Action.RETREAT && memory.valence() < 0) value += 18.0D * weight;
            if (action == Action.PATROL && "attack".equals(memory.type())) {
                value += (life.courage() >= 50 ? 14.0D : -4.0D) * weight;
            }
            if (action == Action.PROTECT && "attack".equals(memory.type())) value += 16.0D * weight;
            if (action == Action.WORK && "reward".equals(memory.type())) value += 12.0D * weight;
            if (action == Action.WORK && "wage_paid".equals(memory.type())) value += 14.0D * weight;
            if (action == Action.WORK && "wage_missed".equals(memory.type())) value -= 18.0D * weight;
            if (action == Action.CARE && "household_food_shortage".equals(memory.type())) value += 18.0D * weight;
            if (action == Action.CARE && "household_care".equals(memory.type())) value += 6.0D * weight;
        }
        return Math.max(-20.0D, Math.min(20.0D, value));
    }

    private static long round(double value) { return Math.round(value); }
}
