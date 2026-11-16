package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import net.minecraft.server.MinecraftServer;

/** Explicit production assembly; no GameTest fixture or duplicate person store. */
public final class WarMusterSources {
    private WarMusterSources(){}
    public static WarCandidateInspector.Sources open(MinecraftServer server){
        return new WarCandidateInspector.Sources(com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(server),com.jgeted.sagadyssey.social.life.NpcLifeData.get(server),
                com.jgeted.sagadyssey.social.combat.InjuryData.get(server),com.jgeted.sagadyssey.social.service.ServiceRelationshipData.get(server),
                new WarCivilResponsibilities(com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData.get(server),com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(server),
                        com.jgeted.sagadyssey.social.needs.SupplyFlowData.get(server),com.jgeted.sagadyssey.social.production.ProductionOrderData.get(server),com.jgeted.sagadyssey.npc.brain.command.NpcDutyData.get(server),
                        com.jgeted.sagadyssey.worldsim.lod.NpcLodData.get(server),com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.get(server)));
    }
}
