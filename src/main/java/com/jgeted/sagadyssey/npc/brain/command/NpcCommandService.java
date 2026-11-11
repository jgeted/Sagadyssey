package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.service.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class NpcCommandService {
    private NpcCommandService() {}
    public record Result(boolean accepted,String detail,NpcCommandIntent intent) {}
    public static Result issue(ServerPlayer player,NpcBase npc,NpcCommand command) {
        BlockPos anchor=command==NpcCommand.PATROL&&npc.getPatrolCenter()!=null?npc.getPatrolCenter():npc.blockPosition();
        return issue(player,npc,command,anchor,Math.max(4,npc.getPatrolRadius()),24000);
    }
    public static Result issue(ServerPlayer player,NpcBase npc,NpcCommand command,BlockPos anchor,int radius,int ticks) {
        if(!npc.isAlive()||player.level().getServer()!=MinecraftApi.server(npc)||player.level()!=npc.level()
                ||ticks<20||ticks>24000||NpcCommandIntent.scope(command)==null||radius<1||radius>48)
            return new Result(false,"命令目标、期限或范围无效",null);
        long now=npc.level().getGameTime();UUID issuer=CharacterRegistry.ensureCurrent(player).id();
        var services=ServiceRelationshipData.get(player.level().getServer());
        var verdict=services.authorize(issuer,npc.getCharacterId(),NpcCommandIntent.scope(command),false,now);
        if(!verdict.allowed())return new Result(false,verdict.reason(),null);
        var proofs=services.relationships().stream().filter(r->verdict.relationshipIds().contains(r.id())
                &&within(r,npc)).map(ServiceRelationship::id).toList();
        if(proofs.isEmpty())return new Result(false,"命令超出约定的地域范围",null);
        var data=NpcCommandData.get(player.level().getServer());
        // Latest queued request replaces only the pending request, never the running obligation.
        data.queued(npc.getCharacterId()).ifPresent(i->data.put(i.update(NpcCommandIntent.Status.CANCELLED,i.leg(),now,"被后续待执行命令取代")));
        var prior=data.active(npc.getCharacterId()).map(NpcCommandIntent::previous).orElse(npc.getCommand());
        var intent=new NpcCommandIntent(UUID.randomUUID(),npc.getCharacterId(),issuer,player.getUUID(),proofs,command,
                prior,npc.level().dimension().identifier().toString(),anchor,radius,now,now+ticks,NpcCommandIntent.Status.QUEUED,0,now,
                "普通命令排队，不抢占既有义务；发现威胁时由战斗桥接管");
        data.put(intent);if(!NpcBrainRuntime.isPlanned(npc))activate(npc,intent,now);
        return new Result(true,NpcBrainRuntime.isPlanned(npc)?"命令已登记，当前计划结束后执行":"已下达有限期命令",data.find(intent.id()));
    }
    private static boolean within(ServiceRelationship r,NpcBase npc){return (r.limits().dimension().isEmpty()
            ||r.limits().dimension().equals(npc.level().dimension().identifier().toString()))
            &&(r.limits().workplaceSettlementId()==null||r.limits().workplaceSettlementId().equals(com.jgeted.sagadyssey.social.work.NpcWorkContext.from(npc).workplaceSettlementId()));}
    /** Revalidate the original proof; a later unrelated contract cannot revive a revoked request. */
    public static boolean authorized(NpcBase npc,NpcCommandIntent intent,long now) {
        return intent.character().equals(npc.getCharacterId())&&intent.dimension().equals(npc.level().dimension().identifier().toString())
                &&ServiceRelationshipData.get(MinecraftApi.server(npc)).relationships().stream().anyMatch(r->intent.evidence().contains(r.id())
                &&r.principalId().equals(intent.issuer())&&r.agentId().equals(intent.character())&&r.activeAt(now)
                &&r.scopes().contains(NpcCommandIntent.scope(intent.command()))&&within(r,npc));
    }
    public static void maintain(NpcBase npc) {
        long now=npc.level().getGameTime();var data=NpcCommandData.get(MinecraftApi.server(npc));
        // An explicit server-side/admin override must not be silently undone by an old intent.
        data.active(npc.getCharacterId()).filter(i->npc.getCommand()!=i.command()).ifPresent(i->
                data.put(i.update(NpcCommandIntent.Status.CANCELLED,i.leg(),now,"服务端明确命令覆盖；旧请求不再复活")));
        var current=new ArrayList<NpcCommandIntent>();data.active(npc.getCharacterId()).ifPresent(current::add);data.queued(npc.getCharacterId()).ifPresent(current::add);
        for(var i:current)if(!i.closed()
                &&(now>=i.expires()||!npc.isAlive()||!authorized(npc,i,now))){
            data.put(i.update(now>=i.expires()?NpcCommandIntent.Status.EXPIRED:NpcCommandIntent.Status.CANCELLED,i.leg(),now,
                    now>=i.expires()?"命令到期":"原授权已撤销、越界或人物死亡"));
            if(i.status()==NpcCommandIntent.Status.ACTIVE){
                if(NpcBrainRuntime.isPlanned(npc))NpcBrainRuntime.cancel(npc,PlanCancellationReason.AUTHORIZED_COMMAND,"命令到期/原授权失效，停止命令下的计划");
                npc.setCommand(i.previous());npc.getNavigation().stop();
            }
        }
        // Safe boundary: no executor is running. Combat never activates an ordinary request.
        if((!NpcBrainRuntime.isPlanned(npc)||NpcBrainRuntime.data(MinecraftApi.server(npc)).active(npc.getUUID()).isEmpty())
                &&npc.getTarget()==null&&npc.isAlive())data.queued(npc.getCharacterId()).ifPresent(i->activate(npc,i,now));
    }
    private static void activate(NpcBase npc,NpcCommandIntent i,long now){
        var data=NpcCommandData.get(MinecraftApi.server(npc));data.active(npc.getCharacterId()).ifPresent(old->data.put(old.update(NpcCommandIntent.Status.CANCELLED,old.leg(),now,"在安全边界由新命令取代")));
        data.put(i.update(NpcCommandIntent.Status.ACTIVE,i.leg(),now,"授权重新核验，进入有限任务"));npc.setCommand(i.command());
        if(i.command()==NpcCommand.PATROL)npc.setPatrol(i.anchor(),i.radius());
    }
}
