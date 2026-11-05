package com.jgeted.sagadyssey.npc.brain.candidate;

import java.util.Objects;

/**
 * 候选在调度阶段未被接受的可诊断原因。
 */
public record CandidateRejection(String candidateKey, Code code, String detail) {
    public CandidateRejection {
        if (candidateKey == null || candidateKey.isBlank()) {
            throw new IllegalArgumentException("candidateKey must not be blank");
        }
        Objects.requireNonNull(code, "code");
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }

    public enum Code {
        HARD_BLOCKER,
        TOO_EARLY,
        EXPIRED,
        MISSING_PREREQUISITE,
        COMMITMENT_LOCKED
    }
}
