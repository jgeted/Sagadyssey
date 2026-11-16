package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.appearance.NpcClothingVisualEvents;
import com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Presents durable military duties to the shared NPC outfit resolver. */
final class WarClothingProjection {
    private final MinecraftServer server;
    private final WarServerRuntime runtime;

    private WarClothingProjection(MinecraftServer server, WarServerRuntime runtime) {
        this.server = server;
        this.runtime = runtime;
    }

    static void bind(MinecraftServer server, WarServerRuntime runtime) {
        NpcClothingVisualEvents.bindOutfitOverlay(server, new WarClothingProjection(server, runtime)::outfit);
    }

    private String outfit(NpcBase npc) {
        if (!server.isSameThread()) throw new IllegalStateException("military outfit requires server thread");
        var person = npc.getCharacterId();
        var active = runtime.mobilization().flatMap(muster -> muster.activePerson(person));
        if (active.isEmpty()) return "";

        var request = active.get().request();
        if (!runtime.servicePermits(request, ((ServerLevel) npc.level()).getGameTime())) return "";
        var body = CharacterRegistryData.get(server).bodyOf(person);
        if (body == null || !body.entityId().equals(npc.getUUID())) return "";

        if (WarCampaignRuntime.existing(server)
                .filter(campaign -> campaign.hasTransportDuty(request.id(), person, npc.getUUID())).isPresent()) {
            return NpcMilitaryRole.MILITARY_CARRIER.id();
        }
        var role = WarStaffRuntime.existing(server).flatMap(staff -> staff.visibleRole(person, request.id()))
                .map(post -> switch (post) {
                    case STANDARD_BEARER -> NpcMilitaryRole.STANDARD_BEARER;
                    case CAPTAIN -> NpcMilitaryRole.TROOP_CAPTAIN;
                    case TEMPORARY_DEPUTY, DEPUTY -> NpcMilitaryRole.DEPUTY;
                    case STAFF_OFFICER -> NpcMilitaryRole.STAFF_OFFICER;
                    case ENGINEERING_LEAD -> NpcMilitaryRole.ENGINEERING_LEAD;
                    case QUARTERMASTER -> NpcMilitaryRole.QUARTERMASTER;
                }).orElseGet(() -> switch (request.role()) {
                    case STANDARD_BEARER -> NpcMilitaryRole.STANDARD_BEARER;
                    case ENGINEER -> NpcMilitaryRole.ENGINEER;
                    case COURIER -> NpcMilitaryRole.MILITARY_CARRIER;
                    case STAFF -> NpcMilitaryRole.STAFF_OFFICER;
                    default -> null;
                });
        if (role != null) return role.id();
        return InitialWarDefinitions.LOADOUTS.find(request.loadout())
                .map(loadout -> loadout.mount().key() + "_" + loadout.primaryWeapon().key()).orElse("");
    }
}
