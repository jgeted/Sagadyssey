package com.jgeted.sagadyssey.social.religion;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.institution.*;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Idempotent physical binding; no generated chest contents, NPCs, faction changes or inferred powers. */
public final class ChapelBootstrapService{
    private ChapelBootstrapService(){}
    public static TempleRecord bind(MinecraftServer server,String buildingKey,BlockPos treasuryPos,BlockPos almsPos,BlockPos counter,SubjectRef founder,int retained,int portion){
        var building=SettlementRegistryData.get(server).building(buildingKey);
        if(building==null||!"church".equals(building.category()))throw new IllegalArgumentException("registered church building required");
        var d=ReligionData.get(server);UUID id=ContainerIds.named("local-temple:"+buildingKey);var owner=SubjectRef.institution(id);
        long now=server.overworld().getGameTime();var old=d.forBuilding(buildingKey);
        if(old!=null){if(!old.treasury().position().equals(treasuryPos)||!old.alms().position().equals(almsPos)||!old.counter().equals(counter)||old.retained()!=retained||old.portion()!=portion)throw new IllegalStateException("existing chapel binding cannot be overwritten");return old;}
        d.canAddTemple();var rights=RightsStateData.get(server);String dim=building.dimension();
        var treasury=point(server,dim,treasuryPos,owner,"temple_treasury");var alms=point(server,dim,almsPos,owner,"temple_alms");
        var level=SupplyFlowService.level(server,dim);
        if(level==null||!level.hasChunkAt(counter)||!level.getBlockState(counter).getCollisionShape(level,counter).isEmpty()
                ||!level.getBlockState(counter.above()).getCollisionShape(level,counter.above()).isEmpty()
                ||!level.getBlockState(counter.below()).isCollisionShapeFullBlock(level,counter.below()))throw new IllegalArgumentException("real usable alms counter required");
        for(var p:List.of(treasuryPos,almsPos,counter))if(p.distSqr(building.position())>96*96)throw new IllegalArgumentException("facility outside local church binding");
        var t=new TempleRecord(id,buildingKey,"地方教堂",treasury,alms,counter,retained,portion,now);
        // All physical/title preflight happens before establishing the accountable social body.
        var institutions=InstitutionData.get(server);
        if(institutions.institution(id)==null)InstitutionService.establish(institutions,rights,id,t.name(),InstitutionKind.TEMPLE,building.settlementId(),treasury.containerId(),founder,
                Set.of(OfficeAuthority.APPOINT_OFFICER,OfficeAuthority.MANAGE_TREASURY,OfficeAuthority.AUDIT_ACCOUNTS),now,"独立财库；济贫保留量="+retained+"；单次上限="+portion);
        else if(institutions.institution(id).kind()!=InstitutionKind.TEMPLE||!institutions.institution(id).treasuryContainerId().equals(treasury.containerId()))throw new IllegalStateException("institution binding conflict");
        for(var p:List.of(treasury,alms)){rights.ensureContext(p.containerId(),dim+":"+p.position().asLong(),owner,owner,AccessPolicy.INSTITUTION);SupplyFlowData.get(server).register(p);}
        d.temple(t);return t;
    }
    private static FieldworkStorage point(MinecraftServer server,String dim,BlockPos p,SubjectRef owner,String reason){
        UUID id=ProductionContainerIds.resolve(server,dim,p);var context=RightsStateData.get(server).context(id);
        if(context!=null&&!context.defaultBeneficialOwner().equals(owner))throw new IllegalStateException("existing facility ownership differs; lawful title transfer required");
        var point=new FieldworkStorage(dim,p,id,owner,AccessPolicy.INSTITUTION,TransferIntent.DELIVERY,reason);
        if(SupplyFlowService.container(server,point)==null)throw new IllegalArgumentException("physical loaded treasury/alms container required");return point;
    }
    public static InstitutionOffice appoint(MinecraftServer server,TempleRecord temple,SubjectRef actor,NpcBase holder,TempleRecord.Role role){
        if(!holder.level().dimension().identifier().toString().equals(temple.alms().dimension()))throw new IllegalArgumentException("office holder must be in local dimension");
        var d=InstitutionData.get(server);long now=server.overworld().getGameTime();
        if(!d.authority(actor,temple.id(),OfficeAuthority.APPOINT_OFFICER,now).allowed())throw new IllegalStateException("no appointment authority");
        String title=switch(role){case CLERGY_OFFICIANT->"主持司祭";case DEACON_STEWARD->"济贫执事";case COMMUNITY_RELIGIOUS->"社区修士/修女";case SCRIBE_TEACHER->"抄写员/教员";case CHAPEL_WATCHMAN->"教堂看守";case TEMPLE_REPRESENTATIVE->"地方教堂协定代表";};
        var person=SubjectRef.character(holder.getCharacterId());var existing=d.officesHeld(temple.id(),person,now).stream().filter(o->o.title().equals(title)).findFirst().orElse(null);if(existing!=null)return existing;
        if(d.membership(temple.id(),person)==null)d.addMembership(new InstitutionMembership(UUID.randomUUID(),temple.id(),person,MembershipRank.OFFICER,0,now,true,"地方教堂任职；不转移私人财产"));
        var powers=switch(role){case CLERGY_OFFICIANT->Set.of(OfficeAuthority.PERFORM_PUBLIC_RITE,OfficeAuthority.REVIEW_ALMS,OfficeAuthority.CERTIFY_VITAL_RECORD);case DEACON_STEWARD->Set.of(OfficeAuthority.MANAGE_ALMS,OfficeAuthority.AUDIT_ACCOUNTS);case COMMUNITY_RELIGIOUS->Set.of(OfficeAuthority.COMMUNITY_CARE);case SCRIBE_TEACHER->Set.of(OfficeAuthority.TEACH_PUBLICLY,OfficeAuthority.MANAGE_LIBRARY);case CHAPEL_WATCHMAN->Set.of(OfficeAuthority.MAINTAIN_FACILITY,OfficeAuthority.WATCH_PREMISES);case TEMPLE_REPRESENTATIVE->Set.of(OfficeAuthority.NEGOTIATE_TREATY);};
        return InstitutionService.appoint(d,temple.id(),actor,person,title,powers,now,0);
    }
}
