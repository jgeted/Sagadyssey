package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import java.util.*;

/** 持久容器引用；只解析登记身体或已加载的单个方块，既不寻人也不加载区块。 */
public record ItemLocation(UUID id, Kind kind, UUID owner, UUID character, String dimension, long position) {
    public enum Kind { PERSONAL, EQUIPMENT, BLOCK, PLAYER }
    private static final String MARKER = "SagadysseyContainerIdentityV1";
    public ItemLocation {
        Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(owner); Objects.requireNonNull(character); Objects.requireNonNull(dimension);
        if (kind == Kind.BLOCK && (Identifier.tryParse(dimension) == null || !owner.equals(id) || !character.equals(id))
                || kind != Kind.BLOCK && (!dimension.isEmpty() || position != 0 || !id.equals(kind == Kind.PERSONAL
                ? ContainerIds.npcPersonal(character) : kind == Kind.PLAYER ? ContainerIds.playerPersonal(character) : ContainerIds.named("npc_equipment:" + character)))) throw new IllegalArgumentException("invalid item location");
    }
    public static ItemLocation personal(UUID character, UUID body) { return new ItemLocation(ContainerIds.npcPersonal(character), Kind.PERSONAL, body, character, "", 0); }
    public static ItemLocation equipment(UUID character, UUID body) { return new ItemLocation(ContainerIds.named("npc_equipment:" + character), Kind.EQUIPMENT, body, character, "", 0); }
    public static ItemLocation player(UUID character, UUID account) { return new ItemLocation(ContainerIds.playerPersonal(character), Kind.PLAYER, account, character, "", 0); }
    /** Account identity alone never revives a previous life's inventory endpoint. */
    public Optional<net.minecraft.server.level.ServerPlayer> player(MinecraftServer server, CharacterRegistryData characters) {
        requireThread(server); if(kind!=Kind.PLAYER)return Optional.empty();var current=characters.current(owner);var p=server.getPlayerList().getPlayer(owner);
        return current!=null&&current.id().equals(character)&&p!=null&&p.isAlive()&&!p.isRemoved()?Optional.of(p):Optional.empty();
    }
    public static ItemLocation block(UUID id, String dimension, BlockPos position) { return new ItemLocation(id, Kind.BLOCK, id, id, dimension, position.asLong()); }
    /** 可信配置入口；给原方块打身份标记，防止原地换箱后被视为同一容器。物权仍须另行正式登记。 */
    public void bindBlock(MinecraftServer server) {
        requireThread(server); if (kind != Kind.BLOCK) throw new IllegalStateException("not a block reference");
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension))); var pos = BlockPos.of(position);
        if (level == null || !level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof Container)) throw new IllegalStateException("real single block container unavailable");
        var block = level.getBlockEntity(pos); var tag = block.getPersistentData();
        if (tag.contains(MARKER) && (!tag.read(MARKER, UUIDUtil.CODEC).isPresent() || !id.equals(tag.read(MARKER, UUIDUtil.CODEC).orElseThrow()))) throw new IllegalStateException("block already has another container identity");
        tag.store(MARKER, UUIDUtil.CODEC, id); block.setChanged();
    }
    public Optional<WitnessedItemEndpoint> resolve(MinecraftServer server, CharacterRegistryData characters) {
        requireThread(server);
        if(kind==Kind.PLAYER)return player(server,characters).map(p->WitnessedItemEndpoint.forOwner(id,p.getInventory(),p::getPersistentData,
                ()->player(server,characters).filter(now->now==p).isPresent(),()->p.getInventory().setChanged()));
        if (kind == Kind.BLOCK) {
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension))); var pos = BlockPos.of(position);
            var chunk = level == null ? null : level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || !(chunk.getBlockEntity(pos) instanceof Container container)) return Optional.empty();
            var block = chunk.getBlockEntity(pos); var tag = block.getPersistentData();
            return tag.read(MARKER, UUIDUtil.CODEC).isPresent() && id.equals(tag.read(MARKER, UUIDUtil.CODEC).orElseThrow()) ? Optional.of(WitnessedItemEndpoint.block(id, block, container)) : Optional.empty();
        }
        return body(server, characters).map(npc -> kind == Kind.PERSONAL ? WitnessedItemEndpoint.personal(npc) : WitnessedItemEndpoint.equipment(npc));
    }
    public Optional<NpcBase> body(MinecraftServer server, CharacterRegistryData characters) {
        requireThread(server); if (kind == Kind.BLOCK || kind == Kind.PLAYER) return Optional.empty();
        var binding = characters.bodyOf(character); if (binding == null || !binding.entityId().equals(owner)) return Optional.empty();
        var dim = Identifier.tryParse(binding.dimensionId()); if (dim == null) return Optional.empty();
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dim)); var entity = level == null ? null : level.getEntity(owner);
        return entity instanceof NpcBase npc && npc.isAlive() && npc.getCharacterId().equals(character) ? Optional.of(npc) : Optional.empty();
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.store("Id", UUIDUtil.CODEC, id); tag.putString("Kind", kind.name()); tag.store("Owner", UUIDUtil.CODEC, owner);
        tag.store("Character", UUIDUtil.CODEC, character); tag.putString("Dimension", dimension); tag.putLong("Position", position); return tag;
    }
    public static ItemLocation load(CompoundTag tag) {
        if (!tag.keySet().equals(Set.of("Id", "Kind", "Owner", "Character", "Dimension", "Position")) || !tag.read("Id", UUIDUtil.CODEC).isPresent() || !tag.read("Owner", UUIDUtil.CODEC).isPresent()
                || !tag.read("Character", UUIDUtil.CODEC).isPresent() || !MinecraftApi.containsTag(tag, "Kind", Tag.TAG_STRING) || !MinecraftApi.containsTag(tag, "Dimension", Tag.TAG_STRING) || !MinecraftApi.containsTag(tag, "Position", Tag.TAG_LONG)) throw new IllegalArgumentException("invalid location schema");
        return new ItemLocation(tag.read("Id", UUIDUtil.CODEC).orElseThrow(), Kind.valueOf(tag.getStringOr("Kind", "")), tag.read("Owner", UUIDUtil.CODEC).orElseThrow(), tag.read("Character", UUIDUtil.CODEC).orElseThrow(), tag.getStringOr("Dimension", ""), tag.getLongOr("Position", 0L));
    }
    private static void requireThread(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("item location requires server thread"); }
}
