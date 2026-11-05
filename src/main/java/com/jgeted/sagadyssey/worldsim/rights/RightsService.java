package com.jgeted.sagadyssey.worldsim.rights;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Deterministic, structured rights resolution. Physical custody never implies title transfer. */
public final class RightsService {
    private final RightsStateData state;

    public RightsService(RightsStateData state) {
        this.state = state;
    }

    public RightsVerdict can(SubjectRef actor, AssetRight right, UUID containerId,
                             int slot, ItemFingerprint fingerprint, long gameTime) {
        ContainerContext context = state.context(containerId);
        if (context == null) return RightsVerdict.deny("未登记的容器");

        var claims = state.claimsAt(containerId, slot, fingerprint);
        Set<SubjectRef> owners = new HashSet<>();
        claims.forEach(claim -> owners.add(claim.beneficialOwner()));
        if (owners.size() > 1) {
            return new RightsVerdict(RightsVerdict.Status.CONTESTED,
                    claims.stream().map(AssetClaim::provenance).toList(), "同一批资产存在冲突主张");
        }

        AssetClaim claim = claims.isEmpty() ? null : claims.getFirst();
        SubjectRef owner = claim == null ? context.defaultBeneficialOwner() : claim.beneficialOwner();
        SubjectRef custodian = claim == null ? context.custodian() : claim.custodian();
        if (actor.equals(owner)) return RightsVerdict.allow("直接受益所有权");
        if (actor.equals(custodian) && (right == AssetRight.POSSESS || right == AssetRight.ACCESS)) {
            return RightsVerdict.allow("实际保管关系");
        }
        for (RightGrant grant : state.grantsFor(containerId, actor, gameTime)) {
            if (grant.rights().contains(right)) return RightsVerdict.allow(grant.basis());
        }
        if (context.accessPolicy() == AccessPolicy.PUBLIC
                && (right == AssetRight.ACCESS || right == AssetRight.POSSESS)) {
            return RightsVerdict.allow("公共访问规则");
        }
        if (actor.equals(custodian) && right == AssetRight.TRANSFER) {
            return RightsVerdict.deny("保管者没有处分权");
        }
        return RightsVerdict.deny("没有 " + right.name() + " 权利");
    }
}
