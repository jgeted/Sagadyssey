package com.jgeted.sagadyssey.npc.brain.fieldwork;

import java.util.Objects;
import java.util.Optional;

/**
 * 单方块作业的提交边界。正式实体适配器必须在服务端主线程调用，并提供完整回滚快照。
 * 普通保存重进使用持久回执去重；此接口不承诺跨多个存档文件的断电原子性。
 */
public final class FieldworkCommitService {
    private FieldworkCommitService() {
    }

    public interface Ledger {
        Optional<FieldworkActionReceipt> receipt(String key);
        /** 原子插入；若抛出异常，不能留下回执。 */
        void record(FieldworkActionReceipt receipt);
    }

    @FunctionalInterface
    public interface Preparation {
        /** 只预演，不能修改世界、库存、物权或动作计数。 */
        Mutation prepare(FieldworkAction action, long now);
    }

    public interface Mutation {
        /** 包括区块、目标快照、工作权限、互斥预留、种子/工具、精确产物容量的复核。 */
        Optional<Blocker> validate();
        FieldworkActionReceipt preview();
        /** 只修改 action.target；返回 false 也必须能回滚。 */
        boolean applySingleBlock();
        /** 一并提交投入/产出、工具损耗和产物物权，不能把背包溢出偷偷丢到地上。 */
        void applyInventoryAndRights();
        /** 恢复方块、库存、工具、物权及其版本；不会删除其他计划的预留。 */
        void rollback();
    }

    public record Blocker(String code, String detail) {
        public Blocker {
            FieldworkAction.requireText(code, 128, "code");
            FieldworkAction.requireText(detail, 2_048, "detail");
        }
    }

    public enum Status { COMMITTED, ALREADY_COMMITTED, BLOCKED }

    public record Result(Status status, Optional<FieldworkActionReceipt> receipt,
                         Optional<Blocker> blocker) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(receipt, "receipt");
            Objects.requireNonNull(blocker, "blocker");
            if ((status == Status.BLOCKED) != blocker.isPresent()
                    || (status != Status.BLOCKED) != receipt.isPresent()) {
                throw new IllegalArgumentException("inconsistent commit result");
            }
        }
    }

    public static Result commit(Ledger ledger, FieldworkAction action, long now,
                                Preparation preparation) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(preparation, "preparation");
        if (now < 0L) throw new IllegalArgumentException("negative game time");
        // 去重与提交共用账本锁；不同 NPC 不能对同一步骤制造两个回执。
        synchronized (ledger) {
            Optional<FieldworkActionReceipt> existing = ledger.receipt(action.key());
            if (existing.isPresent()) {
                if (!existing.get().action().equals(action)) {
                    throw new IllegalStateException("fieldwork action key reused with different payload");
                }
                return new Result(Status.ALREADY_COMMITTED, existing, Optional.empty());
            }
            Mutation mutation = Objects.requireNonNull(preparation.prepare(action, now), "mutation");
            Optional<Blocker> blocker = Objects.requireNonNull(mutation.validate(), "validation");
            if (blocker.isPresent()) return new Result(Status.BLOCKED, Optional.empty(), blocker);
            FieldworkActionReceipt receipt = Objects.requireNonNull(mutation.preview(), "receipt");
            if (!receipt.action().equals(action) || receipt.committedAt() != now) {
                throw new IllegalStateException("preview receipt does not match the submitted action");
            }
            try {
                if (mutation.applySingleBlock()) {
                    mutation.applyInventoryAndRights();
                    ledger.record(receipt);
                    return new Result(Status.COMMITTED, Optional.of(receipt), Optional.empty());
                }
            } catch (RuntimeException exception) {
                try {
                    mutation.rollback();
                } catch (RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                // 回滚不完整绝不能伪装成普通等待或成功，交给实体执行器报告失败。
                throw exception;
            }
            mutation.rollback();
            return new Result(Status.BLOCKED, Optional.empty(), Optional.of(
                    new Blocker("BLOCK_UPDATE_REJECTED", "目标方块修改被拒绝，已恢复投入")));
        }
    }
}
