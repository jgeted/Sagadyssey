package com.jgeted.sagadyssey.social.war.api;

import java.util.*;

/** 临时观察结果，不存档、不构成人物占用或共享领域版本锁。 */
public record WarCandidateReport(UUID requestId, UUID characterId, long observedAtTick,
                                 Optional<Person> person, Optional<WarEquipmentSnapshot> equipment,
                                 Set<WarEquipmentSnapshot.Mismatch> equipmentGaps, List<Problem> problems) {
    public record Person(UUID bodyId, String dimension, String profession, String workAuthority,
                         float health, float maxHealth, int hunger, long needsUpdatedAt) {
        public Person {
            Objects.requireNonNull(bodyId); Objects.requireNonNull(dimension); Objects.requireNonNull(profession);
            Objects.requireNonNull(workAuthority);
            if (!Float.isFinite(health) || !Float.isFinite(maxHealth) || maxHealth <= 0 || health < 0
                    || hunger < 0 || hunger > 1000 || needsUpdatedAt < 0) throw new IllegalArgumentException("invalid observed person");
        }
        public int satietyPercent() { return (1000 - hunger) / 10; }
    }
    public record Problem(String code, Optional<UUID> reference) {
        public Problem {
            if (code == null || !code.matches("[A-Z][A-Z_]{0,79}")) throw new IllegalArgumentException("invalid candidate reason");
            Objects.requireNonNull(reference);
        }
        public static Problem of(String code) { return new Problem(code, Optional.empty()); }
        public static Problem of(String code, UUID reference) { return new Problem(code, Optional.of(reference)); }
    }
    public WarCandidateReport {
        Objects.requireNonNull(requestId); Objects.requireNonNull(characterId); Objects.requireNonNull(person);
        Objects.requireNonNull(equipment); equipmentGaps = Set.copyOf(equipmentGaps); problems = List.copyOf(problems);
        if (observedAtTick < 0 || problems.size() > 128 || equipment.isEmpty() && !equipmentGaps.isEmpty()) {
            throw new IllegalArgumentException("invalid candidate observation");
        }
    }
    /** 装备缺口可在领用阶段补齐；这里不将缺装备等同于人物不能参加。 */
    public boolean observedFactsClear() { return person.isPresent() && problems.isEmpty(); }
    public static WarCandidateReport unavailable(WarMilitiaRequest request, long now, String reason) {
        return new WarCandidateReport(request.id(), request.characterId(), now, Optional.empty(), Optional.empty(), Set.of(), List.of(Problem.of(reason)));
    }
}
