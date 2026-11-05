package com.jgeted.sagadyssey.worldsim.transfer;

import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable ids for physical containers that do not already have a domain id. */
public final class ContainerIds {
    private ContainerIds() {}

    public static UUID npcPersonal(UUID characterId) { return named("npc_personal:" + characterId); }
    public static UUID npcCargo(UUID characterId) { return named("npc_cargo:" + characterId); }
    public static UUID playerPersonal(UUID characterId) { return named("player_personal:" + characterId); }

    public static UUID block(String role, String dimension, BlockPos position) {
        return named("block:" + role + ':' + dimension + ':' + position.asLong());
    }

    public static UUID named(String key) {
        return UUID.nameUUIDFromBytes(("sagadyssey:v2:" + key).getBytes(StandardCharsets.UTF_8));
    }
}
