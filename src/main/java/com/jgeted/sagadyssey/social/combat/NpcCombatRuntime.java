package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.economy.SettlementEconomyData;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Real entity evidence bridge. It never grants attack permission or sends backend results to ordinary players. */
public final class NpcCombatRuntime {
    private NpcCombatRuntime(){}
    public static void wounded(NpcBase npc,UUID attacker,UUID wound){
        long now=npc.level().getGameTime();var encounters=CombatEncounterData.get(MinecraftApi.server(npc));var e=encounters.activeFor(npc.getCharacterId(),now);
        if(e==null&&attacker!=null&&!attacker.equals(npc.getCharacterId())){
            var context=com.jgeted.sagadyssey.social.work.NpcWorkContext.from(npc);
            e=CombatEncounterService.start(encounters,UUID.randomUUID(),context.workplaceSettlementId(),npc.level().dimension().identifier().toString(),npc.blockPosition(),32,
                    List.of(new CombatSide(UUID.randomUUID(),"实际受击方",null,Set.of(npc.getCharacterId()),EngagementRule.SELF_DEFENSE_ONLY),
                            new CombatSide(UUID.randomUUID(),"实际攻击者",null,Set.of(attacker),EngagementRule.SELF_DEFENSE_ONLY)),now);
        }
        if(e!=null)record(npc,e,wound,EncounterEventType.WOUNDED,wound,attacker,"真实受击伤口，不是命令授权");
    }
    public static void shot(NpcBase npc,UUID arrow){var e=CombatEncounterData.get(MinecraftApi.server(npc)).activeFor(npc.getCharacterId(),npc.level().getGameTime());
        if(e!=null){record(npc,e,arrow,EncounterEventType.SHOT,arrow,null,"消耗实体箭矢并生成对应投射物");
            if(!npc.getPersonalInventory().contains(s->s.getItem() instanceof net.minecraft.world.item.ArrowItem))record(npc,e,named(arrow+":last"),EncounterEventType.AMMUNITION_EXHAUSTED,arrow,null,"最后一根真实箭矢已发射");}}
    public static void retreat(NpcBase npc,UUID receipt){var e=CombatEncounterData.get(MinecraftApi.server(npc)).activeFor(npc.getCharacterId(),npc.level().getGameTime());if(e!=null)record(npc,e,receipt,EncounterEventType.RETREATED,receipt,null,"真实撤离意图；不自动回血、不宣告胜利");}
    public static void cargoDropped(NpcBase npc,net.minecraft.world.entity.item.ItemEntity drop){var e=CombatEncounterData.get(MinecraftApi.server(npc)).activeFor(npc.getCharacterId(),npc.level().getGameTime());if(e!=null)
        record(npc,e,drop.getUUID(),EncounterEventType.CARGO_DROPPED,drop.getUUID(),null,"真实掉落 "+drop.getItem().getCount()+" "+com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint.of(drop.getItem())+"；不是交付或永久销毁");}
    public static void death(NpcBase npc){var e=CombatEncounterData.get(MinecraftApi.server(npc)).activeFor(npc.getCharacterId(),npc.level().getGameTime());if(e!=null)record(npc,e,named(e.id()+":death:"+npc.getCharacterId()),EncounterEventType.KILLED,null,null,"实体死亡事件；遗产仍由人物生命周期模块处理");}
    private static void record(NpcBase npc,CombatEncounter e,UUID id,EncounterEventType type,UUID source,UUID other,String text){
        CombatEncounterService.record(CombatEncounterData.get(MinecraftApi.server(npc)),CombatMoraleData.get(MinecraftApi.server(npc)),e.id(),
                new EncounterEvent(id,e.id(),type,npc.getCharacterId(),other,source,npc.level().getGameTime(),text));}
    public static void maintain(NpcBase npc){if(npc.level().isClientSide()||MinecraftApi.server(npc)==null||npc.tickCount%20!=0)return;
        if(NaturalConflictData.engaged(MinecraftApi.server(npc),npc.getCharacterId()))return;
        long now=npc.level().getGameTime();var data=CombatEncounterData.get(MinecraftApi.server(npc));var e=data.activeFor(npc.getCharacterId(),now);if(e==null)return;
        if(npc.getTarget()!=null&&npc.getTarget().isAlive())return;
        long latest=e.events().stream().mapToLong(EncounterEvent::occurredAt).max().orElse(e.startedAt());if(now-latest<120)return;
        var level=(ServerLevel)npc.level();var nearby=level.getEntitiesOfClass(NpcBase.class,npc.getBoundingBox().inflate(64),n->e.sideOf(n.getCharacterId())!=null);
        if(nearby.stream().anyMatch(n->n.getTarget()!=null&&n.getTarget().isAlive()||now-n.getLastDamageGameTime()<120&&n.getLastDamageGameTime()>=0))return;
        finish(level,e.id(),now);
    }
    /** Domain inboxes commit before the encounter retires, so replay repairs an interrupted writeback. */
    public static void finish(ServerLevel level,UUID encounter,long now){var data=CombatEncounterData.get(level.getServer());var e=data.encounter(encounter);if(e==null||e.status()==EncounterStatus.ENDED)return;
        var homes=HouseholdData.get(level.getServer());var life=NpcLifeData.get(level.getServer());
        var impacts=data.freeze(encounter,()->{
            var snapshot=new ArrayList<CombatImpact>();
            for(var side:e.sides())for(var character:side.members()){
            var home=homes.forMember(character);var contract=life.contract(character);var personal=e.events().stream().filter(v->v.subjectCharacterId().equals(character)).toList();
            var impact=new CombatImpact(e.id(),character,home==null?null:home.id(),contract==null?null:contract.employerCharacterId(),e.settlementId(),
                    (int)personal.stream().filter(v->v.type()==EncounterEventType.WOUNDED).count(),(int)personal.stream().filter(v->v.type()==EncounterEventType.SHOT).count(),
                    (int)personal.stream().filter(v->v.type()==EncounterEventType.CARGO_DROPPED).count(),personal.stream().anyMatch(v->v.type()==EncounterEventType.RETREATED||v.type()==EncounterEventType.ROUTED),now);
            snapshot.add(impact);
            }return snapshot;
        });
        for(var impact:impacts){
            var character=impact.character();
            if(impact.household()!=null)homes.recordCombatImpact(impact);
            if(impact.employer()!=null)life.recordCombatImpact(impact);
            if(impact.settlement()!=null)SettlementEconomyData.get(level.getServer()).recordCombatImpact(impact);
            // Only the person's own experience enters their memory. Absent family/employer gains no private knowledge.
            if(life.record(character)!=null)life.remember(character,new NpcMemoryRecord("battle:"+e.id(),null,"本人经历战斗结束；来源遭遇="+e.id()+"；伤口="+impact.wounds()+"，射箭="+impact.shots()+"，掉落货堆="+impact.droppedStacks(),-5,40,e.startedAt(),e.startedAt()+72000),now);
        }
        data.update(new CombatEncounter(e.id(),e.settlementId(),e.dimension(),e.center(),e.radius(),e.startedAt(),impacts.getFirst().settled(),EncounterStatus.ENDED,e.sides(),e.events(),e.revision()+1));
    }
    private static UUID named(String text){return UUID.nameUUIDFromBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
