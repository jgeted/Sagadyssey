package com.jgeted.sagadyssey.social.war.spi;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarBodyCheckpoint;
import java.util.*;

/** A/S在装配边界绑定原生身体/物品类型；此处没有原生实体、物品或NBT依赖。 */
public final class WarFieldPorts {
    private WarFieldPorts() {}
    public enum AttackStatus { HIT, NO_HIT, LAUNCHED, BLOCKED, UNSUPPORTED }
    public record Support(boolean implemented, String reason) {
        public Support { Objects.requireNonNull(reason); }
    }
    public record AttackResult(AttackStatus status, String reason) {
        public AttackResult { Objects.requireNonNull(status); Objects.requireNonNull(reason); }
    }
    public interface Items<S> { WarWeaponProfile inspect(S stack); }
    public enum ChangeStatus { READY, PENDING, BLOCKED, REVIEW }
    public record Arms(WarWeaponProfile held, Optional<WarWeaponProfile.Ranged> primaryRanged, boolean backupHeld, ChangeStatus status, String reason) {}
    public record WeaponChange(ChangeStatus status, String reason) {}
    public interface Actors<A> {
        Support support(WarAttackKind kind);
        WarLoadout.Mount mount(A actor);
        void stopNavigation(A actor);
        void clearTarget(A actor);
        void engage(A actor, A target);
        boolean canStand(A actor, WarIntent.Position destination);
        boolean navigate(A actor, WarIntent.Position destination, double speed);
        AttackResult attack(A actor, A target, WarAttackKind kind);
        /** Actual preparation time, scoped to one received order/sortie; no damage or velocity is granted. */
        default void prepareCharge(A actor,A target,UUID operation,WarAttackKind kind) {}
        default void cancelCharge(A actor) {}
        Arms arms(A actor, UUID contract, WarAttackKind primary);
        WeaponChange equip(A actor, UUID contract, WarAttackKind primary, WarAttackKind desired);
        void settleEquipment(A actor);
        boolean hasAmmunition(A actor);
        boolean isRangedUsing(A actor, WarAttackKind kind);
        void beginRangedUse(A actor, WarAttackKind kind);
        void cancelRangedUse(A actor);
        AttackResult rangedAttack(A actor, A target, WarAttackKind kind, WarShotPermit permit);
    }
    public enum ReadStatus { ABSENT, PRESENT, INVALID }
    public record CheckpointRead(ReadStatus status, Optional<WarBodyCheckpoint> value) {
        public CheckpointRead {
            Objects.requireNonNull(status); Objects.requireNonNull(value);
            if ((status == ReadStatus.PRESENT) != value.isPresent()) throw new IllegalArgumentException("invalid checkpoint read");
        }
    }
    public interface Checkpoints<A> {
        CheckpointRead read(A actor);
        /** 写入原实体待保存数据；不是持久Journal的APPLIED确认。 */
        void store(A actor, WarBodyCheckpoint value);
    }
}
