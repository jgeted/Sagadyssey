package com.jgeted.sagadyssey.npc.brain.plan;

import com.jgeted.sagadyssey.npc.brain.candidate.CandidateRejection;
import com.jgeted.sagadyssey.npc.brain.candidate.NpcActivityCandidate;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/** 调度器的可解释、无副作用结果。 */
public record NpcSchedulingDecision(Action action,
                                    @Nullable NpcActivityCandidate selectedCandidate,
                                    Map<String, Double> eligibleScores,
                                    List<CandidateRejection> rejections,
                                    String explanation) {
    public NpcSchedulingDecision {
        if (action == null) throw new IllegalArgumentException("action is required");
        eligibleScores = Map.copyOf(eligibleScores == null ? Map.of() : eligibleScores);
        rejections = List.copyOf(rejections == null ? List.of() : rejections);
        explanation = explanation == null ? "" : explanation;
        if ((action == Action.START || action == Action.REPLACE)
                != (selectedCandidate != null)) {
            throw new IllegalArgumentException("start or replace requires a candidate");
        }
        if ((action == Action.KEEP || action == Action.CANCEL || action == Action.NONE)
                && selectedCandidate != null) {
            throw new IllegalArgumentException("keep, cancel or none cannot carry a candidate");
        }
    }

    public enum Action {
        START,
        KEEP,
        REPLACE,
        CANCEL,
        NONE
    }
}
