package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.core.network.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarMapRuntime;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarManagementRuntime;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

/** Independent military protocol; no client classes, civilian protocol changes, or arbitrary coordinate packets. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarMapNetwork12111 {
    public record RequestPayload(Request value) implements CustomPacketPayload {
        public static final Type<RequestPayload> TYPE = new Type<>(Identifier.parse("sagadyssey:war_map_action_v2"));
        public static final StreamCodec<ByteBuf,RequestPayload> CODEC = StreamCodec.of(
                (b, p) -> writeRequest(b, p.value()), b -> new RequestPayload(readRequest(b)));
        public RequestPayload { Objects.requireNonNull(value); }
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record UpdatePayload(Update value) implements CustomPacketPayload {
        public static final Type<UpdatePayload> TYPE = new Type<>(Identifier.parse("sagadyssey:war_map_view_v2"));
        public static final StreamCodec<ByteBuf,UpdatePayload> CODEC = StreamCodec.of(
                (b, p) -> write(b, p.value()), b -> new UpdatePayload(read(b)));
        public UpdatePayload { Objects.requireNonNull(value); }
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        var registrar=event.registrar("war-map-2");
        registrar.playToServer(RequestPayload.TYPE,RequestPayload.CODEC,(r,c)->c.enqueueWork(()->{
            if (c.player() instanceof ServerPlayer p && NetworkRateLimiter.allow(p,"war_map",2)) {
                var update = WarMapRuntime.get(p.level().getServer()).act(p, r.value());
                PacketDistributor.sendToPlayer(p, new UpdatePayload(update));
            }
        }));
        registrar.playToClient(UpdatePayload.TYPE,UpdatePayload.CODEC);
    }

    public static boolean open(ServerPlayer player) {
        if (WarMapRuntime.sheet(player).isEmpty() || CharacterRegistry.currentId(player) == null || player.isSpectator() || !player.isAlive()) {
            player.displayClientMessage(Component.literal("请手持当前维度的有效地图，以当前人物打开军令地图。"), true);
            return false;
        }
        var runtime = WarMapRuntime.get(player.level().getServer());
        try {
            WarManagementRuntime.leave(player);
            PacketDistributor.sendToPlayer(player, new UpdatePayload(runtime.begin(player)));
            return true;
        } catch (IllegalStateException e) {
            player.displayClientMessage(Component.literal("军令地图会话已满，请稍后重试。"), true);
            return false;
        }
    }

    private static void writeRequest(ByteBuf b, Request r) {
        uuid(b,r.session());b.writeLong(r.revision());b.writeByte(r.action().ordinal());b.writeInt(r.page());
        uuid(b,r.unit());b.writeByte(r.mode().ordinal());b.writeInt(r.pixelX());b.writeInt(r.pixelZ());uuid(b,r.ticket());
    }

    private static Request readRequest(ByteBuf b) {
        return new Request(uuid(b),b.readLong(),value(b,Action.values()),b.readInt(),uuid(b),
                value(b,WarMapView.Mode.values()),b.readInt(),b.readInt(),uuid(b));
    }
    private static void write(ByteBuf b,Update update){
        int start=b.writerIndex();uuid(b,update.session());b.writeBoolean(update.openScreen());text(b,update.message());b.writeBoolean(update.view().isPresent());
        if(update.view().isPresent()){
            var v=update.view().get();b.writeLong(v.revision());b.writeLong(v.observedAt());b.writeLong(v.expiresAt());var s=v.sheet();b.writeInt(s.id());key(b,s.dimension());b.writeInt(s.centerX());b.writeInt(s.centerZ());b.writeByte(s.scale());b.writeInt(v.page());b.writeInt(v.totalUnits());b.writeByte(v.units().size());
            for(var u:v.units()){
                uuid(b,u.id());uuid(b,u.army());key(b,u.loadout());b.writeLong(u.revision());b.writeInt(u.members());b.writeInt(u.reports());b.writeInt(u.organization());b.writeInt(u.pending());b.writeBoolean(u.observation().isPresent());
                u.observation().ifPresent(o->{position(b,o.position());b.writeLong(o.observedAt());b.writeByte(o.state().ordinal());});
                text(b,u.name());
            }
            b.writeBoolean(v.preview().isPresent());v.preview().ifPresent(p->{uuid(b,p.ticket());uuid(b,p.unit());b.writeByte(p.mode().ordinal());position(b,p.target());b.writeLong(p.expiresAt());b.writeByte(p.delivery().ordinal());b.writeLong(p.delay());});
            b.writeBoolean(v.receipt().isPresent());v.receipt().ifPresent(r->{b.writeByte(r.outcome().ordinal());text(b,r.reason());b.writeBoolean(r.order().isPresent());r.order().ifPresent(id->uuid(b,id));b.writeLong(r.dueAt());});
        }
        if(b.writerIndex()-start>16_384)throw new IllegalArgumentException("map payload exceeds 16 KiB");
    }
    private static Update read(ByteBuf b){
        int start=b.readerIndex();var session=uuid(b);boolean open=b.readBoolean();String message=text(b);Optional<WarMapView> view=Optional.empty();
        if(b.readBoolean()){
            long revision=b.readLong(),now=b.readLong(),expires=b.readLong();var s=new WarMapSheet(b.readInt(),key(b),b.readInt(),b.readInt(),b.readUnsignedByte());int page=b.readInt(),total=b.readInt(),count=b.readUnsignedByte();
            if(count>WarMapView.PAGE_SIZE)throw new DecoderException("map page too large");var units=new ArrayList<WarMapView.Unit>();
            for(int i=0;i<count;i++){
                var id=uuid(b);var army=uuid(b);var loadout=key(b);long rev=b.readLong();int members=b.readInt(),reports=b.readInt(),org=b.readInt(),pending=b.readInt();
                var observed=b.readBoolean()?Optional.of(new WarMapView.Observation(position(b),b.readLong(),value(b,WarFieldReport.State.values()))):Optional.<WarMapView.Observation>empty();
                units.add(new WarMapView.Unit(id,army,loadout,rev,members,reports,org,pending,observed,text(b)));
            }
            var preview=b.readBoolean()?Optional.of(new WarMapView.Preview(uuid(b),uuid(b),value(b,WarMapView.Mode.values()),position(b),b.readLong(),value(b,WarMapView.Delivery.values()),b.readLong())):Optional.<WarMapView.Preview>empty();
            Optional<WarMapView.Receipt> receipt=Optional.empty();if(b.readBoolean()){var outcome=value(b,WarMapView.Outcome.values());var reason=text(b);var order=b.readBoolean()?Optional.of(uuid(b)):Optional.<UUID>empty();receipt=Optional.of(new WarMapView.Receipt(outcome,reason,order,b.readLong()));}
            view=Optional.of(new WarMapView(session,revision,now,expires,s,page,total,units,preview,receipt));
        }
        if(b.readerIndex()-start>16_384)throw new DecoderException("map payload exceeds 16 KiB");return new Update(session,open,view,message);
    }
    private static void uuid(ByteBuf b,UUID id){b.writeLong(id.getMostSignificantBits());b.writeLong(id.getLeastSignificantBits());}private static UUID uuid(ByteBuf b){return new UUID(b.readLong(),b.readLong());}
    private static void text(ByteBuf b,String v){NetworkStrings.write(b,v,1024);}private static String text(ByteBuf b){return NetworkStrings.read(b,1024);}
    private static void key(ByteBuf b,WarKey k){text(b,k.toString());}private static WarKey key(ByteBuf b){return WarKey.parse(text(b));}
    private static void position(ByteBuf b,WarIntent.Position p){key(b,p.dimension());b.writeInt(p.x());b.writeInt(p.y());b.writeInt(p.z());}private static WarIntent.Position position(ByteBuf b){return new WarIntent.Position(key(b),b.readInt(),b.readInt(),b.readInt());}
    private static <T>T value(ByteBuf b,T[] values){int i=b.readUnsignedByte();if(i>=values.length)throw new DecoderException("invalid map enum");return values[i];}
}
