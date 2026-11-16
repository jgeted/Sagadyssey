package com.jgeted.sagadyssey.social.service;

import com.jgeted.sagadyssey.social.life.NpcAgencyDecision.ServiceOfferDecision;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiskDelegationTest {
    private static final Set<AuthorityScope> SCOPES=Set.of(AuthorityScope.ASSIGN_ROLE,AuthorityScope.DEFEND_PRINCIPAL,AuthorityScope.MANAGE_ISSUED_EQUIPMENT);
    private static final ServiceLimits LIMITS=new ServiceLimits(null,"minecraft:overworld",true,false);
    private static final ServiceOfferDecision YES=new ServiceOfferDecision(true,.5,"accepted"),NO=new ServiceOfferDecision(false,.5,"declined");
    @Test void aNarrowRiskAgreementDoesNotReplaceOrdinaryEmploymentOrGrantPrivateTitle(){
        var d=new ServiceRelationshipData();var parent=d.beginVoluntaryEmployment(UUID.randomUUID(),UUID.randomUUID(),10);var id=UUID.randomUUID();
        d.acceptRiskDelegation(id,parent.principalId(),parent.agentId(),parent.id(),parent.revision(),SCOPES,LIMITS,11,100,YES);
        assertTrue(d.relationship(parent.id()).activeAt(12));assertTrue(d.permits(parent.principalId(),parent.agentId(),SCOPES,true,"minecraft:overworld",null,12));
        assertFalse(d.permits(parent.principalId(),parent.agentId(),SCOPES,true,"minecraft:the_nether",null,12));assertFalse(d.relationship(id).limits().privateInventoryAccess());
        d.endDelegation(id,parent.principalId(),parent.agentId(),"withdraw",13);assertTrue(d.relationship(parent.id()).activeAt(14));assertFalse(d.permits(parent.principalId(),parent.agentId(),SCOPES,true,"minecraft:overworld",null,14));
        assertThrows(IllegalStateException.class,()->d.acceptRiskDelegation(id,parent.principalId(),parent.agentId(),parent.id(),parent.revision(),SCOPES,LIMITS,14,100,YES));assertFalse(d.relationship(id).activeAt(14));
    }
    @Test void refusalAndStaleParentCannotAuthorizeTheDangerousService(){
        var d=new ServiceRelationshipData();var p=d.beginVoluntaryEmployment(UUID.randomUUID(),UUID.randomUUID(),10);
        assertThrows(IllegalArgumentException.class,()->d.acceptRiskDelegation(UUID.randomUUID(),p.principalId(),p.agentId(),p.id(),p.revision(),SCOPES,LIMITS,11,100,NO));
        d.endActive(p.principalId(),p.agentId(),ServiceStatus.CANCELLED,"ordinary service ended",12);
        assertThrows(IllegalArgumentException.class,()->d.acceptRiskDelegation(UUID.randomUUID(),p.principalId(),p.agentId(),p.id(),p.revision(),SCOPES,LIMITS,13,100,YES));assertEquals(1,d.relationships().size());
    }
    @Test void anUnrelatedPrincipalCannotGrantOrEndAnotherDelegation(){
        var d=new ServiceRelationshipData();var p=d.beginVoluntaryEmployment(UUID.randomUUID(),UUID.randomUUID(),10);var id=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->d.acceptRiskDelegation(id,UUID.randomUUID(),p.agentId(),p.id(),p.revision(),SCOPES,LIMITS,11,100,YES));
        d.acceptRiskDelegation(id,p.principalId(),p.agentId(),p.id(),p.revision(),SCOPES,LIMITS,11,100,YES);
        assertThrows(IllegalArgumentException.class,()->d.endDelegation(id,UUID.randomUUID(),p.agentId(),"foreign",12));assertTrue(d.relationship(id).activeAt(13));
    }
    @Test void savedRelationsRebuildThePermissionIndexAndKeepTheSameAcceptedIdentity(){
        var d=new ServiceRelationshipData();var p=d.beginVoluntaryEmployment(UUID.randomUUID(),UUID.randomUUID(),10);var id=UUID.randomUUID();var child=d.acceptRiskDelegation(id,p.principalId(),p.agentId(),p.id(),p.revision(),SCOPES,LIMITS,11,100,YES);
        var loaded=ServiceRelationshipData.load(d.save(new CompoundTag(),null),null);assertEquals(child,loaded.relationship(id));assertTrue(loaded.permits(p.principalId(),p.agentId(),SCOPES,true,"minecraft:overworld",null,12));
        assertEquals(child,loaded.acceptRiskDelegation(id,p.principalId(),p.agentId(),p.id(),p.revision(),SCOPES,LIMITS,12,100,YES));assertEquals(2,loaded.relationships().size());
    }
}
