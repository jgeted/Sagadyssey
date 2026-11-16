package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.social.war.spi.WarMobilizationPorts.*;
import java.util.*;

/** 把军事阶段映射为通用粮饷协议；结清协议不要求债务已付清，也不延长劳动占用。 */
public final class WarProvisionAdapter implements EffectPort {
    private final ServiceProvisionService service;
    public WarProvisionAdapter(ServiceProvisionService service) { this.service = Objects.requireNonNull(service); }
    public int protocolVersion() { return 1; }
    public Reply lookup(Effect effect) { return map(effect, service.lookup(effect.request().id(), step(effect.stage()), effect.operationId())); }
    public Reply apply(Effect effect) {
        var old = lookup(effect); if (old.status() != Status.NOT_APPLIED) return old;
        long at = effect.stage() == WarMobilization.Stage.OPEN_PAYROLL ? effect.serviceStartTick()
                : effect.stage() == WarMobilization.Stage.CLOSE_PAYROLL ? effect.serviceEndTick() : effect.now();
        return map(effect, service.mark(effect.request().id(), step(effect.stage()), effect.operationId(), at, effect.now()));
    }
    public Reply cancel(Effect effect) { return lookup(effect); }
    private Reply map(Effect effect, ServiceProvisionService.Reply reply) {
        if (reply.status() == ServiceProvisionService.Status.APPLIED) {
            var mark = reply.mark().orElseThrow();
            boolean debt = mark.outstanding();
            return Reply.applied(new WarMobilization.Receipt(effect.request().id(), effect.request().characterId(), effect.stage(), effect.operationId(),
                    effect.request().id(), mark.acknowledgedAt(), debt));
        }
        return Reply.state(switch (reply.status()) {
            case NOT_APPLIED -> Status.NOT_APPLIED; case PENDING -> Status.PENDING; case UNAVAILABLE -> Status.UNAVAILABLE; default -> Status.UNKNOWN;
        }, reply.reason());
    }
    static ServiceProvision.Step step(WarMobilization.Stage stage) {
        return switch (stage) {
            case OPEN_PAYROLL -> ServiceProvision.Step.OPEN_PAYROLL; case SUSTAIN -> ServiceProvision.Step.SUSTAIN;
            case END_SUSTAIN -> ServiceProvision.Step.END_SUSTAIN; case CLOSE_PAYROLL -> ServiceProvision.Step.CLOSE_PAYROLL;
            default -> throw new IllegalArgumentException("not a provision stage");
        };
    }
}
