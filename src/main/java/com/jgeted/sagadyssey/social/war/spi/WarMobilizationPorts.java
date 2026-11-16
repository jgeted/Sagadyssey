package com.jgeted.sagadyssey.social.war.spi;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;

import java.util.*;

/** 民兵用例所需的窄接点；共享实现不反向依赖战争，转换放在战争适配器中。 */
public final class WarMobilizationPorts {
    public static final WarKey INSPECTION = WarKey.parse("sagadyssey:war/mobilization/inspection");
    public static final WarKey JOURNAL = WarKey.parse("sagadyssey:war/mobilization/durable_journal");
    private WarMobilizationPorts() {}
    public enum Status { APPLIED, NOT_APPLIED, PENDING, UNKNOWN, UNAVAILABLE }
    public interface Versioned { int protocolVersion(); }
    public record Readiness(boolean eligible, List<String> reasons) {
        public Readiness {
            reasons = List.copyOf(reasons);
            if (eligible != reasons.isEmpty()) throw new IllegalArgumentException("readiness and reasons disagree");
        }
    }
    public interface Inspection extends Versioned {
        /** 无写入、无注册/绑定修复；核验权限、生活职业、健康、现有劳动/携货责任及资格。 */
        Readiness inspect(WarMilitiaRequest request, long now);
        /** 对照持久共享占用/工资/物流索引，报告孤立义务或状态冲突，不扫描世界NPC。 */
        Readiness reconcile(Collection<WarMobilization> records);
    }
    public record Effect(WarMilitiaRequest request, WarMobilization.Stage stage, UUID operationId, UUID cancellationId,
                         Map<WarMobilization.Stage, WarMobilization.Receipt> priorReceipts, long now) {
        public Effect { priorReceipts = Map.copyOf(priorReceipts); }
        public long serviceStartTick() { return priorReceipts.get(WarMobilization.Stage.CLAIM).recordedAtTick(); }
        public long serviceEndTick() { return priorReceipts.get(WarMobilization.Stage.RELEASE_LABOR).recordedAtTick(); }
    }
    public record Reply(Status status, Optional<WarMobilization.Receipt> receipt, String reason) {
        public Reply {
            Objects.requireNonNull(status); Objects.requireNonNull(receipt); Objects.requireNonNull(reason);
            if ((status == Status.APPLIED) != receipt.isPresent() || reason.length() > 512) throw new IllegalArgumentException("invalid effect reply");
        }
        public static Reply applied(WarMobilization.Receipt receipt) { return new Reply(Status.APPLIED, Optional.of(receipt), ""); }
        public static Reply state(Status status, String reason) { return new Reply(status, Optional.empty(), reason); }
    }
    public interface EffectPort extends Versioned {
        /** NOT_APPLIED证明原步骤从未生效且无在途效果，不是查询不到回执或已生效后补偿。 */
        Reply lookup(Effect effect);
        /** 同一操作号同一内容幂等；重启、多份SavedData不一致时返回UNKNOWN并保留义务。 */
        Reply apply(Effect effect);
        /** 稳定取消号；已生效必须返回原APPLIED回执，由后续释放/返还步骤补偿并计费。 */
        Reply cancel(Effect effect);
    }
    public record WriteReply(Status status, Optional<WarMobilization> durable, String reason) {
        public WriteReply {
            Objects.requireNonNull(status); Objects.requireNonNull(durable); Objects.requireNonNull(reason);
            if ((status == Status.APPLIED) != durable.isPresent()) throw new IllegalArgumentException("invalid journal acknowledgement");
        }
    }
    public interface Journal extends Versioned, AutoCloseable {
        int capacity();
        Collection<WarMobilization> recover();
        /** APPLIED只在完整请求、步骤与修订号已经落盘后返回；禁止先回执再写入。 */
        WriteReply persist(WarMobilization record);
        @Override void close();
    }
    public record Bindings(Optional<Inspection> inspection, Optional<Journal> journal,
                           Map<WarMobilization.Stage, EffectPort> effects) {
        public Bindings {
            Objects.requireNonNull(inspection); Objects.requireNonNull(journal); effects = Map.copyOf(effects);
        }
        public static Bindings unavailable() { return new Bindings(Optional.empty(), Optional.empty(), Map.of()); }
        public Set<WarKey> missing() {
            Set<WarKey> result = new TreeSet<>();
            if (inspection.isEmpty() || inspection.get().protocolVersion() != 1) result.add(INSPECTION);
            if (journal.isEmpty() || journal.get().protocolVersion() != 1) result.add(JOURNAL);
            for (var stage : WarMobilization.Stage.values()) if (stage.effect()) {
                var port = effects.get(stage);
                if (port == null || port.protocolVersion() != 1) result.add(stage.capability());
            }
            return Collections.unmodifiableSet(result);
        }
    }
}
