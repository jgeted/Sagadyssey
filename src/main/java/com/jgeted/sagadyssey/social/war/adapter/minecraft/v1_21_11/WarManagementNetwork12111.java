package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.core.network.NetworkRateLimiter;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarManagementRuntime;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarMapRuntime;
import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.*;
import com.jgeted.sagadyssey.social.war.api.WarMapSheet;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.*;

import static com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.*;

/** Native item and NPC interactions and a bounded, versioned screen transport. */
@EventBusSubscriber(modid = "sagadyssey")
public final class WarManagementNetwork12111 {
    private WarManagementNetwork12111() {}

    public record Enter(UUID session, long revision) implements CustomPacketPayload {
        public static final Type<Enter> TYPE = new Type<>(Identifier.parse("sagadyssey:war_management_enter_v1"));
        public static final StreamCodec<ByteBuf, Enter> CODEC = StreamCodec.of((b, p) -> {
            uuid(b, p.session()); b.writeLong(p.revision());
        }, b -> new Enter(uuid(b), b.readLong()));
        public Enter {
            Objects.requireNonNull(session);
            if (revision < 1) throw new IllegalArgumentException("panel entry revision");
        }
        public Type<Enter> type() { return TYPE; }
    }

    public record Input(Request value) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(Identifier.parse("sagadyssey:war_management_action_v1"));
        public static final StreamCodec<ByteBuf, Input> CODEC = StreamCodec.of((b, p) -> {
            var r = p.value();
            uuid(b, r.session()); b.writeLong(r.revision()); b.writeByte(r.control());
            b.writeByte(r.x()); b.writeByte(r.z());
            b.writeByte(r.values().size());
            r.values().forEach(text -> NetworkStrings.write(b, text, MAX_VALUE * 4));
        }, b -> {
            var session = uuid(b);
            long revision = b.readLong();
            int control = b.readByte(), x = b.readUnsignedByte(), z = b.readUnsignedByte();
            var values = new ArrayList<String>();
            for (int n = size(b, MAX_FIELDS); n > 0; n--) values.add(NetworkStrings.read(b, MAX_VALUE * 4));
            return new Input(new Request(session, revision, control, values, x, z));
        });
        public Type<Input> type() { return TYPE; }
    }

    public record Output(View value) implements CustomPacketPayload {
        public static final Type<Output> TYPE = new Type<>(Identifier.parse("sagadyssey:war_management_view_v1"));
        public static final StreamCodec<ByteBuf, Output> CODEC = StreamCodec.of((b, p) -> write(b, p.value()), b -> new Output(read(b)));
        public Type<Output> type() { return TYPE; }
    }

    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("war-management-1");
        registrar.playToServer(Enter.TYPE, Enter.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer p) || !NetworkRateLimiter.allow(p, "war_management", 2)) return;
            if (!WarMapRuntime.get(p.level().getServer()).leaveForManagement(p, packet.session(), packet.revision())) {
                p.displayClientMessage(Component.literal("请刷新或重开军令地图。"), true);
                return;
            }
            var opened = WarManagementRuntime.open(p);
            if (opened.isPresent()) send(p, opened.get());
            else p.displayClientMessage(Component.literal("军籍簿暂不可用。"), true);
        }));
        registrar.playToServer(Input.TYPE, Input.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer p && NetworkRateLimiter.allow(p, "war_management", 2))
                WarManagementRuntime.act(p, packet.value()).ifPresent(view -> send(p, view));
        }));
        registrar.playToClient(Output.TYPE, Output.CODEC);
    }

    /** Reserve only the deliberate filled-map conversation; other hands and objects use the NPC's own interaction. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void npc(PlayerInteractEvent.EntityInteractSpecific event) {
        var p = event.getEntity();
        if (event.getHand() != InteractionHand.MAIN_HAND || !p.isShiftKeyDown() || !p.getMainHandItem().is(Items.FILLED_MAP)
                || !(event.getTarget() instanceof NpcBase npc) || p.isSpectator()) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
        if (p instanceof ServerPlayer serverPlayer && NetworkRateLimiter.allow(serverPlayer, "war_management_npc", 4)) {
            var opened = WarManagementRuntime.interact(serverPlayer, npc);
            if (opened.isPresent()) send(serverPlayer, opened.get());
            else p.displayClientMessage(Component.literal("请持本维度地图，与当前人物可管理的原 NPC 交谈。"), true);
        }
    }

    static void block(ServerPlayer player, BlockPos pos, Direction direction) {
        WarManagementRuntime.block(player, pos, direction).ifPresent(view -> send(player, view));
    }

    private static void send(ServerPlayer player, View view) {
        PacketDistributor.sendToPlayer(player, new Output(view));
        if (view.display() == Display.COMMAND_MAP) WarMapNetwork12111.open(player);
    }

    private static void write(ByteBuf b, View v) {
        uuid(b, v.session());
        b.writeLong(v.revision()); b.writeByte(v.display().ordinal());
        NetworkStrings.write(b, v.title(), 384); NetworkStrings.write(b, v.message(), 1024);
        b.writeByte(v.lines().size()); v.lines().forEach(text -> NetworkStrings.write(b, text, 1024));
        b.writeByte(v.fields().size());
        for (var field : v.fields()) {
            NetworkStrings.write(b, field.label(), 256); NetworkStrings.write(b, field.initial(), MAX_VALUE * 4);
            b.writeByte(field.length());
        }
        b.writeByte(v.controls().size()); v.controls().forEach(text -> NetworkStrings.write(b, text, 320));
        b.writeBoolean(v.map().isPresent());
        v.map().ifPresent(map -> {
            b.writeInt(map.id()); NetworkStrings.write(b, map.dimension().toString(), 1024);
            b.writeInt(map.centerX()); b.writeInt(map.centerZ()); b.writeByte(map.scale());
        });
    }

    private static View read(ByteBuf b) {
        var session = uuid(b);
        long revision = b.readLong();
        int display = b.readUnsignedByte();
        if (display >= Display.values().length) throw new DecoderException("invalid military panel display");
        String title = NetworkStrings.read(b, 384), message = NetworkStrings.read(b, 1024);
        List<String> lines = strings(b, MAX_LINES, 1024);
        var fields = new ArrayList<Field>();
        for (int n = size(b, MAX_FIELDS); n > 0; n--)
            fields.add(new Field(NetworkStrings.read(b, 256), NetworkStrings.read(b, MAX_VALUE * 4), b.readUnsignedByte()));
        List<String> controls = strings(b, MAX_CONTROLS, 320);
        Optional<WarMapSheet> map = b.readBoolean() ? Optional.of(new WarMapSheet(b.readInt(), WarKey.parse(NetworkStrings.read(b, 1024)),
                b.readInt(), b.readInt(), b.readUnsignedByte())) : Optional.empty();
        return new View(session, revision, Display.values()[display], title, message, lines, fields, controls, map);
    }

    private static List<String> strings(ByteBuf b, int maxCount, int maxBytes) {
        var result = new ArrayList<String>();
        for (int n = size(b, maxCount); n > 0; n--) result.add(NetworkStrings.read(b, maxBytes));
        return result;
    }

    private static int size(ByteBuf b, int max) {
        int count = b.readUnsignedByte();
        if (count > max) throw new DecoderException("military panel count exceeds bound");
        return count;
    }

    private static void uuid(ByteBuf b, UUID id) { b.writeLong(id.getMostSignificantBits()); b.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(ByteBuf b) { return new UUID(b.readLong(), b.readLong()); }
}
