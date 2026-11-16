package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 可恢复服役流程。回执只引用共享端事实；工资闭合在劳动释放之后，覆盖返还过程。 */
public record WarMobilization(WarMilitiaRequest request, Stage stage, long revision, long changedAtTick,
                               boolean demobilizeRequested, Map<Stage, Receipt> receipts, String reviewReason) {
    public enum Stage {
        CLAIM("claim"), OPEN_PAYROLL("open_payroll"), SUSTAIN("sustain"), EQUIP("equip"), ACTIVATE("activate"), ACTIVE("active"),
        RETURN_EQUIPMENT("return_equipment"), RELEASE_LABOR("release_labor"), END_SUSTAIN("end_sustain"), CLOSE_PAYROLL("close_payroll"), RELEASED("released");
        private final String key;
        Stage(String key) { this.key = key; }
        public String key() { return key; }
        public WarKey capability() { return new WarKey("sagadyssey", "war/mobilization/" + key); }
        public boolean effect() { return this != ACTIVE && this != RELEASED; }
        public static Stage fromKey(String key) {
            return Arrays.stream(values()).filter(stage -> stage.key.equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown mobilization stage"));
        }
    }
    public record Receipt(UUID contractId, UUID characterId, Stage stage, UUID operationId,
                          UUID referenceId, long recordedAtTick, boolean outstandingObligation) {
        public Receipt {
            Objects.requireNonNull(contractId); Objects.requireNonNull(characterId); Objects.requireNonNull(stage);
            Objects.requireNonNull(operationId); Objects.requireNonNull(referenceId);
            if (!stage.effect() || recordedAtTick < 0 || outstandingObligation && stage != Stage.CLOSE_PAYROLL) {
                throw new IllegalArgumentException("invalid mobilization receipt");
            }
        }
    }
    public WarMobilization {
        Objects.requireNonNull(request); Objects.requireNonNull(stage); receipts = Map.copyOf(receipts);
        reviewReason = Objects.requireNonNull(reviewReason);
        if (revision < 1 || changedAtTick < request.requestedAtTick() || reviewReason.length() > 512) {
            throw new IllegalArgumentException("invalid mobilization counters");
        }
        for (var entry : receipts.entrySet()) {
            Receipt receipt = entry.getValue();
            if (entry.getKey() != receipt.stage() || !request.id().equals(receipt.contractId())
                    || !request.characterId().equals(receipt.characterId())
                    || !operationId(request.id(), receipt.stage()).equals(receipt.operationId())
                    || receipt.recordedAtTick() < request.requestedAtTick() || receipt.recordedAtTick() > changedAtTick) {
                throw new IllegalArgumentException("foreign or inconsistent mobilization receipt");
            }
        }
        for (var entry : receipts.entrySet()) requirePredecessors(entry.getKey(), receipts);
        requirePredecessors(stage, receipts);
        boolean cleanup = stage == Stage.RETURN_EQUIPMENT || stage == Stage.RELEASE_LABOR
                || stage == Stage.END_SUSTAIN || stage == Stage.CLOSE_PAYROLL || stage == Stage.RELEASED;
        if (cleanup && !demobilizeRequested) throw new IllegalArgumentException("cleanup without demobilization request");
        if (stage.effect() && receipts.containsKey(stage) || !cleanup && receipts.keySet().stream()
                .anyMatch(step -> step == Stage.RETURN_EQUIPMENT || step == Stage.RELEASE_LABOR || step == Stage.END_SUSTAIN || step == Stage.CLOSE_PAYROLL)) {
            throw new IllegalArgumentException("receipt is ahead of current mobilization stage");
        }
        if (stage == Stage.RELEASED && receipts.containsKey(Stage.CLAIM) && !receipts.containsKey(Stage.CLOSE_PAYROLL)) {
            throw new IllegalArgumentException("released service has no closed payroll responsibility");
        }
        if (stage == Stage.CLAIM && !receipts.isEmpty()) throw new IllegalArgumentException("claim already completed");
    }
    private static void requirePredecessors(Stage stage, Map<Stage, Receipt> receipts) {
        List<Stage> required = switch (stage) {
            case CLAIM, RELEASED -> List.of();
            case OPEN_PAYROLL -> List.of(Stage.CLAIM);
            case SUSTAIN -> List.of(Stage.CLAIM, Stage.OPEN_PAYROLL);
            case EQUIP -> List.of(Stage.CLAIM, Stage.OPEN_PAYROLL, Stage.SUSTAIN);
            case ACTIVATE, ACTIVE, RETURN_EQUIPMENT -> List.of(Stage.CLAIM, Stage.OPEN_PAYROLL, Stage.SUSTAIN, Stage.EQUIP);
            case RELEASE_LABOR -> receipts.containsKey(Stage.EQUIP)
                    ? List.of(Stage.CLAIM, Stage.OPEN_PAYROLL, Stage.RETURN_EQUIPMENT) : List.of(Stage.CLAIM, Stage.OPEN_PAYROLL);
            case END_SUSTAIN -> List.of(Stage.SUSTAIN, Stage.RELEASE_LABOR);
            case CLOSE_PAYROLL -> receipts.containsKey(Stage.SUSTAIN)
                    ? List.of(Stage.CLAIM, Stage.OPEN_PAYROLL, Stage.RELEASE_LABOR, Stage.END_SUSTAIN)
                    : List.of(Stage.CLAIM, Stage.OPEN_PAYROLL, Stage.RELEASE_LABOR);
        };
        if (!receipts.keySet().containsAll(required) || stage == Stage.ACTIVE && !receipts.containsKey(Stage.ACTIVATE)) {
            throw new IllegalArgumentException("missing mobilization predecessor");
        }
        Receipt current = receipts.get(stage);
        if (current != null && required.stream().anyMatch(prior -> receipts.get(prior).recordedAtTick() > current.recordedAtTick())) {
            throw new IllegalArgumentException("mobilization receipt time moved backwards");
        }
    }
    public static UUID operationId(UUID contract, Stage stage) {
        return UUID.nameUUIDFromBytes(("sagadyssey:war/mobilization/" + contract + "/" + stage.key()).getBytes(StandardCharsets.UTF_8));
    }
    public static UUID cancellationId(UUID contract, Stage stage) {
        return UUID.nameUUIDFromBytes(("sagadyssey:war/mobilization/" + contract + "/" + stage.key() + "/cancel").getBytes(StandardCharsets.UTF_8));
    }
    public static WarMobilization start(WarMilitiaRequest request) {
        return new WarMobilization(request, Stage.CLAIM, 1, request.requestedAtTick(), false, Map.of(), "");
    }
    public boolean holdsCharacter() { return stage != Stage.RELEASED && !receipts.containsKey(Stage.RELEASE_LABOR); }
    public boolean hasOutstandingObligation() {
        return receipts.values().stream().anyMatch(Receipt::outstandingObligation);
    }
    public WarMobilization demobilize(long now) {
        if (demobilizeRequested) return this;
        return replace(stage == Stage.ACTIVE ? Stage.RETURN_EQUIPMENT : stage, true, receipts, "", now);
    }
    public WarMobilization review(String reason, long now) {
        if (reviewReason.equals(reason)) return this;
        return replace(stage, demobilizeRequested, receipts, reason, now);
    }
    public WarMobilization confirmed(Receipt receipt, long now) {
        if (receipt.stage() != stage) throw new IllegalArgumentException("receipt for another effect");
        Map<Stage, Receipt> next = new EnumMap<>(Stage.class); next.putAll(receipts); next.put(stage, receipt);
        Stage following = switch (stage) {
            case CLAIM -> Stage.OPEN_PAYROLL;
            case OPEN_PAYROLL -> demobilizeRequested ? cleanup(next) : Stage.SUSTAIN;
            case SUSTAIN -> demobilizeRequested ? cleanup(next) : Stage.EQUIP;
            case EQUIP -> demobilizeRequested ? cleanup(next) : Stage.ACTIVATE;
            case ACTIVATE -> demobilizeRequested ? cleanup(next) : Stage.ACTIVE;
            case RETURN_EQUIPMENT -> Stage.RELEASE_LABOR;
            case RELEASE_LABOR -> next.containsKey(Stage.SUSTAIN) ? Stage.END_SUSTAIN : Stage.CLOSE_PAYROLL;
            case END_SUSTAIN -> Stage.CLOSE_PAYROLL;
            case CLOSE_PAYROLL -> Stage.RELEASED;
            default -> throw new IllegalStateException("not an effect stage");
        };
        return replace(following, demobilizeRequested, next, "", now);
    }
    /** 只在共享端明确证明当前步骤没有效果时使用，不能用缺少回执代替此证明。 */
    public WarMobilization cancelUnapplied(long now) {
        if (!demobilizeRequested || stage != Stage.CLAIM && stage != Stage.SUSTAIN && stage != Stage.EQUIP && stage != Stage.ACTIVATE) {
            throw new IllegalStateException("effect cannot be skipped");
        }
        return replace(stage == Stage.CLAIM ? Stage.RELEASED : cleanup(receipts), true, receipts, "", now);
    }
    private static Stage cleanup(Map<Stage, Receipt> receipts) {
        return receipts.containsKey(Stage.EQUIP) ? Stage.RETURN_EQUIPMENT : Stage.RELEASE_LABOR;
    }
    private WarMobilization replace(Stage stage, boolean demobilize, Map<Stage, Receipt> receipts, String reason, long now) {
        if (now < changedAtTick || revision == Long.MAX_VALUE) throw new IllegalArgumentException("mobilization time or revision exhausted");
        return new WarMobilization(request, stage, revision + 1, now, demobilize, receipts, reason);
    }
}
