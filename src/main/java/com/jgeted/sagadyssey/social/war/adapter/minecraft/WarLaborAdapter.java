package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import com.jgeted.sagadyssey.social.work.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 战争映射到共享劳动；共享模块不依赖战争，也不存战争的第二份人物事实。 */
public final class WarLaborAdapter implements EffectPort {
    private final WorkCommitmentService work;
    private final WarCandidateInspector inspector;
    public WarLaborAdapter(WorkCommitmentService work, WarCandidateInspector inspector) { this.work = Objects.requireNonNull(work); this.inspector = Objects.requireNonNull(inspector); }
    public int protocolVersion() { return 1; }
    public Reply lookup(Effect effect) {
        var intent = intent(effect.request());
        return intent.isEmpty() ? Reply.state(Status.UNAVAILABLE, "REGISTERED_BODY_REQUIRED") : map(effect, work.lookup(intent.get(), operation(effect.stage()), effect.operationId()));
    }
    public Reply apply(Effect effect) {
        var prior = lookup(effect); if (prior.status() != Status.NOT_APPLIED) return prior;
        var intent = intent(effect.request()); if (intent.isEmpty()) return Reply.state(Status.UNAVAILABLE, "REGISTERED_BODY_REQUIRED");
        if (effect.stage() == WarMobilization.Stage.CLAIM) {
            if (!inspector.originalResponsibilitiesPermitClaim(effect.request().characterId(),effect.request().id())) return Reply.state(Status.UNAVAILABLE, "SHARED_RESPONSIBILITIES_REQUIRE_HANDOFF");
            var observed = inspector.inspect(List.of(effect.request()), effect.now()).getFirst();
            if (!observed.observedFactsClear()) return Reply.state(Status.UNAVAILABLE, "CIVIL_RESPONSIBILITIES_OR_QUALIFICATION_REQUIRE_HANDOFF");
            return map(effect, work.acquire(intent.get(), effect.operationId(), effect.now()));
        }
        if (effect.stage() == WarMobilization.Stage.ACTIVATE && !inspector.bodyAvailable(intent.get().character(), intent.get().body())) {
            return Reply.state(Status.UNAVAILABLE, "ORIGINAL_BODY_UNAVAILABLE");
        }
        if (effect.stage() == WarMobilization.Stage.ACTIVATE && !inspector.inspect(List.of(effect.request()), effect.now()).getFirst().observedFactsClear()) {
            return Reply.state(Status.UNAVAILABLE, "QUALIFICATION_CHANGED_BEFORE_ACTIVATION");
        }
        return map(effect, work.advance(intent.get(), operation(effect.stage()), effect.operationId(), effect.now()));
    }
    /** 接受占用写入后等待其原回执，再按正式复员释放；不把已准备的占用伪装为未发生。 */
    public Reply cancel(Effect effect) { return lookup(effect); }
    private Optional<WorkCommitment.Intent> intent(WarMilitiaRequest request) {
        var body = work.find(request.id()).map(record -> record.intent().body()).or(() -> inspector.registeredBody(request.characterId()));
        return body.map(id -> new WorkCommitment.Intent(request.id(), request.characterId(), id, "sagadyssey:war", request.armyId(), request.actorId(), fingerprint(request)));
    }
    private Reply map(Effect effect, WorkCommitmentService.Reply reply) {
        if (reply.status() == WorkCommitmentService.Status.APPLIED) return Reply.applied(new WarMobilization.Receipt(effect.request().id(), effect.request().characterId(),
                effect.stage(), effect.operationId(), effect.request().id(), reply.receipt().orElseThrow().occurredAt(), false));
        return Reply.state(switch (reply.status()) {
            case NOT_APPLIED -> Status.NOT_APPLIED;
            case PENDING -> Status.PENDING;
            case UNAVAILABLE, BUSY -> Status.UNAVAILABLE;
            default -> Status.UNKNOWN;
        }, reply.reason());
    }
    private static WorkCommitment.Operation operation(WarMobilization.Stage stage) {
        return switch (stage) {
            case CLAIM -> WorkCommitment.Operation.CLAIM;
            case ACTIVATE -> WorkCommitment.Operation.ACTIVATE;
            case RELEASE_LABOR -> WorkCommitment.Operation.RELEASE;
            default -> throw new IllegalArgumentException("not a shared labor step");
        };
    }
    static String fingerprint(WarMilitiaRequest r) {
        String canonical = String.join("|", "war-militia-v1", r.id().toString(), r.actorId().toString(), r.armyId().toString(), r.unitId().toString(),
                r.characterId().toString(), r.loadout().toString(), Integer.toString(r.definitionVersion()), r.kind().name(), r.role().name(),
                r.wage().currency().toString(), Long.toString(r.wage().amountPerPeriod()), Long.toString(r.wage().periodTicks()), Long.toString(r.requestedAtTick()));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
