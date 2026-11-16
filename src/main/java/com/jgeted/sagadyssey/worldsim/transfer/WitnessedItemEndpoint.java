package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.nbt.*;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;
import java.util.function.*;

/** 效果凭据与真实物品由同一实体/单个方块实体保存；拒绝跨实体合成容器。 */
public final class WitnessedItemEndpoint {
    private static final String KEY = "SagadysseyItemOperationWitnessesV1";
    private final UUID id;
    private final Container container;
    private final Supplier<CompoundTag> ownerData;
    private final BooleanSupplier available;
    private final Runnable changed;
    private WitnessedItemEndpoint(UUID id, Container container, Supplier<CompoundTag> ownerData, BooleanSupplier available, Runnable changed) {
        this.id = Objects.requireNonNull(id); this.container = Objects.requireNonNull(container); this.ownerData = ownerData; this.available = available; this.changed = changed;
    }
    /** 供其他容器适配器使用；必须保证库存和此持久字段由同一个原子保存对象拥有。 */
    public static WitnessedItemEndpoint forOwner(UUID id, Container container, Supplier<CompoundTag> ownerData, BooleanSupplier available, Runnable changed) {
        return new WitnessedItemEndpoint(id, container, Objects.requireNonNull(ownerData), Objects.requireNonNull(available), Objects.requireNonNull(changed));
    }
    public static WitnessedItemEndpoint personal(NpcBase npc) {
        UUID character = npc.getCharacterId();
        return new WitnessedItemEndpoint(ContainerIds.npcPersonal(character), npc.getPersonalInventory(), npc::getPersistentData,
                () -> !npc.isRemoved() && character.equals(npc.getCharacterId()) && npc.getServer() != null && npc.getServer().isSameThread(), () -> npc.getPersonalInventory().setChanged());
    }
    public static WitnessedItemEndpoint equipment(NpcBase npc) {
        UUID character = npc.getCharacterId(); var view = new com.jgeted.sagadyssey.npc.inventory.NpcEquipmentInventory(npc, true);
        return new WitnessedItemEndpoint(ContainerIds.named("npc_equipment:" + character), view, npc::getPersistentData,
                () -> !npc.isRemoved() && character.equals(npc.getCharacterId()) && npc.getServer() != null && npc.getServer().isSameThread(), view::setChanged);
    }
    public static WitnessedItemEndpoint block(UUID id, BlockEntity owner, Container container) {
        if (owner != container) throw new IllegalArgumentException("only a single container block entity has co-located evidence");
        return new WitnessedItemEndpoint(id, container, owner::getPersistentData,
                () -> {
                    if (!(owner.getLevel() instanceof net.minecraft.server.level.ServerLevel level) || !level.getServer().isSameThread() || owner.isRemoved()) return false;
                    var pos = owner.getBlockPos(); var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                    return chunk != null && chunk.getBlockEntity(pos) == owner;
                }, owner::setChanged);
    }
    public UUID id() { return id; }
    public Container container() { return container; }
    public boolean available() { return available.getAsBoolean(); }
    public Optional<CompoundTag> witness(UUID operation) {
        var root = ownerData.get(); if (!root.contains(KEY)) return Optional.empty();
        if (!(root.get(KEY) instanceof CompoundTag entries)) throw new IllegalStateException("invalid container operation evidence");
        var value = entries.get(operation.toString()); if (value == null) return Optional.empty();
        if (!(value instanceof CompoundTag proof)) throw new IllegalStateException("invalid container operation witness"); return Optional.of(proof.copy());
    }
    public boolean canRecord(UUID operation) {
        var root = ownerData.get(); if (!root.contains(KEY)) return true;
        if (!(root.get(KEY) instanceof CompoundTag entries)) return false;
        return entries.contains(operation.toString()) || entries.size() < 4096;
    }
    public void record(UUID operation, CompoundTag proof) {
        var existing = witness(operation); if (existing.isPresent() && !existing.get().equals(proof)) throw new IllegalStateException("container witness identity conflict");
        if (!available() || !canRecord(operation)) throw new IllegalStateException("container evidence unavailable/full");
        var root = ownerData.get(); var entries = root.getCompoundOrEmpty(KEY).copy(); entries.put(operation.toString(), proof.copy()); root.put(KEY, entries); changed.run();
    }
}
