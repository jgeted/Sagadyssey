package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.service.ServiceRelationship;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Army;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.Terms;
import com.jgeted.sagadyssey.social.war.domain.WarState;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarMinecraft12111;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 唯一原生版本装配点。迁移时显式替换实现，核心不以反射或版本号猜测能力。 */
public record WarNativeBindings(String minecraftVersion, WarFieldPorts.Items<ItemStack> items,
                                 WarFieldPorts.Actors<NpcBase> actors, WarFieldPorts.Checkpoints<NpcBase> checkpoints,
                                 com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.Mobility<NpcBase> mobility,
                                 WarEngineeringAdapter engineering,WarHazardAdapter hazards,
                                 Function<MinecraftServer, ServiceCheckpoint> serviceCheckpoints, StaffOfficeFactory staffOffices) {
    public WarNativeBindings {
        Objects.requireNonNull(minecraftVersion); Objects.requireNonNull(items); Objects.requireNonNull(actors);
        Objects.requireNonNull(checkpoints); Objects.requireNonNull(mobility); Objects.requireNonNull(engineering);
        Objects.requireNonNull(hazards); Objects.requireNonNull(serviceCheckpoints); Objects.requireNonNull(staffOffices);
    }
    private static final WarNativeBindings CURRENT = WarMinecraft12111.bindings();
    public static WarNativeBindings currentBuild() { return CURRENT; }

    /** A published readback of shared obligations together with the military core save. */
    public interface ServiceCheckpoint {
        void request();
        void tick(long now);
        Optional<WarState> core();
        boolean contains(ServiceRelationship value);
        String problem();
    }

    @FunctionalInterface
    public interface StaffOfficeFactory {
        StaffOffices open(MinecraftServer server, Set<UUID> knownAppointments) throws IOException;
    }

    /** Binds appointments to the original institutions and their persisted authority. */
    public interface StaffOffices {
        String problem();
        boolean exists(UUID id);
        boolean matches(Terms terms, boolean revoked);
        boolean durable(Terms terms, boolean revoked);
        boolean active(Terms terms, long now);
        void establish(Army army, UUID account);
        void appoint(Terms terms);
        void revoke(Terms terms);
        void request();
        void tick(long now);
    }
}
