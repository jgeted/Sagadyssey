package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarServerEvents;
import com.jgeted.sagadyssey.social.war.api.WarShotPermit;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import java.util.*;

/** 标记只作用于战争模块发射的箭；碰撞时仍核验原遭遇和当前授权，不让在途箭误伤己方。 */
@EventBusSubscriber(modid = "sagadyssey")
public final class WarProjectileSafety {
    public static final String KEY = "SagadysseyWarShotV1";
    private static final Set<String> FIELDS = Set.of("Contract", "Character", "Body", "Army", "Unit", "Order", "Issuer", "Encounter", "Target", "Immediate", "FiredAt");
    private WarProjectileSafety() {}
    public static void mark(AbstractArrow arrow, WarShotPermit value) {
        arrow.getPersistentData().put(KEY, encode(value));
    }
    public static CompoundTag encode(WarShotPermit value) {
        var tag = new CompoundTag(); tag.store("Contract", UUIDUtil.CODEC, value.contract()); tag.store("Character", UUIDUtil.CODEC, value.character()); tag.store("Body", UUIDUtil.CODEC, value.body());
        tag.store("Army", UUIDUtil.CODEC, value.army()); tag.store("Unit", UUIDUtil.CODEC, value.unit()); tag.store("Order", UUIDUtil.CODEC, value.order()); tag.store("Issuer", UUIDUtil.CODEC, value.issuer());
        tag.store("Encounter", UUIDUtil.CODEC, value.encounter()); tag.store("Target", UUIDUtil.CODEC, value.intendedTarget()); tag.putBoolean("Immediate", value.immediateThreat()); tag.putLong("FiredAt", value.firedAt());
        return tag;
    }
    public static Optional<WarShotPermit> read(AbstractArrow arrow) {
        return decode(arrow.getPersistentData().get(KEY));
    }
    public static Optional<WarShotPermit> decode(Tag value) {
        if (!(value instanceof CompoundTag tag) || !tag.keySet().equals(FIELDS)) return Optional.empty();
        for (String key : List.of("Contract", "Character", "Body", "Army", "Unit", "Order", "Issuer", "Encounter", "Target")) if (!tag.read(key, UUIDUtil.CODEC).isPresent()) return Optional.empty();
        if (!MinecraftApi.containsTag(tag, "Immediate", Tag.TAG_BYTE) || tag.getByteOr("Immediate", (byte)0) < 0 || tag.getByteOr("Immediate", (byte)0) > 1 || !MinecraftApi.containsTag(tag, "FiredAt", Tag.TAG_LONG)) return Optional.empty();
        try { return Optional.of(new WarShotPermit(tag.read("Contract", UUIDUtil.CODEC).orElseThrow(), tag.read("Character", UUIDUtil.CODEC).orElseThrow(), tag.read("Body", UUIDUtil.CODEC).orElseThrow(), tag.read("Army", UUIDUtil.CODEC).orElseThrow(), tag.read("Unit", UUIDUtil.CODEC).orElseThrow(), tag.read("Order", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Issuer", UUIDUtil.CODEC).orElseThrow(), tag.read("Encounter", UUIDUtil.CODEC).orElseThrow(), tag.read("Target", UUIDUtil.CODEC).orElseThrow(), tag.getBooleanOr("Immediate", false), tag.getLongOr("FiredAt", 0L))); }
        catch (IllegalArgumentException exception) { return Optional.empty(); }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void impact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow) || arrow.level().isClientSide() || !arrow.getPersistentData().contains(KEY)
                || !(event.getRayTraceResult() instanceof EntityHitResult hit)) return;
        var permit = read(arrow); var server = MinecraftApi.server(arrow);
        var target=hit.getEntity() instanceof NpcBase npc?npc:hit.getEntity() instanceof net.minecraft.world.entity.animal.equine.Horse horse && horse.getPassengers().size()==1 && horse.getFirstPassenger() instanceof NpcBase rider?rider:null;
        boolean allowed = server != null && target != null && permit.isPresent()
                && arrow.getOwner() != null && arrow.getOwner().getUUID().equals(permit.get().body())
                && WarServerEvents.existing(server).flatMap(runtime -> runtime.field()).filter(field -> field.mayImpact(permit.get(), target)).isPresent();
        if (!allowed) { arrow.discard(); event.setCanceled(true); }
    }
}
