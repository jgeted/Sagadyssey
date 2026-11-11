package com.jgeted.sagadyssey.npc.command.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.npc.command.CommandRank;
import com.jgeted.sagadyssey.npc.command.PlayerCommandData;
import com.jgeted.sagadyssey.npc.command.SquadStrategy;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 服务端到客户端的集团快照，同时驱动界面和世界高亮。 */
public record CommandGroupSyncPayload(
        boolean openScreen,
        String activeSquadId,
        List<SquadView> squads,
        List<MemberView> members,
        BlockPos corner1,
        BlockPos corner2
) implements CustomPacketPayload {

    public record SquadView(String id, String name, int color, String strategy, int memberCount) {}
    public record MemberView(String uuid, int entityId, String name, String squadId,
                             String rank, int color, boolean selected) {}

    public static final Type<CommandGroupSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "command_group_sync"));

    public static final StreamCodec<ByteBuf, CommandGroupSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.openScreen);
                writeString(buf, payload.activeSquadId);
                buf.writeInt(payload.squads.size());
                for (SquadView squad : payload.squads) {
                    writeString(buf, squad.id);
                    writeString(buf, squad.name);
                    buf.writeInt(squad.color);
                    writeString(buf, squad.strategy);
                    buf.writeInt(squad.memberCount);
                }
                buf.writeInt(payload.members.size());
                for (MemberView member : payload.members) {
                    writeString(buf, member.uuid);
                    buf.writeInt(member.entityId);
                    writeString(buf, member.name);
                    writeString(buf, member.squadId);
                    writeString(buf, member.rank);
                    buf.writeInt(member.color);
                    buf.writeBoolean(member.selected);
                }
                writePos(buf, payload.corner1);
                writePos(buf, payload.corner2);
            },
            buf -> {
                boolean open = buf.readBoolean();
                String active = readString(buf);
                int squadCount = Math.max(0, Math.min(64, buf.readInt()));
                List<SquadView> squads = new ArrayList<>(squadCount);
                for (int i = 0; i < squadCount; i++) {
                    squads.add(new SquadView(readString(buf), readString(buf), buf.readInt(),
                            readString(buf), buf.readInt()));
                }
                int memberCount = Math.max(0, Math.min(4096, buf.readInt()));
                List<MemberView> members = new ArrayList<>(memberCount);
                for (int i = 0; i < memberCount; i++) {
                    members.add(new MemberView(readString(buf), buf.readInt(), readString(buf),
                            readString(buf), readString(buf), buf.readInt(), buf.readBoolean()));
                }
                return new CommandGroupSyncPayload(open, active, squads, members, readPos(buf), readPos(buf));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static CommandGroupSyncPayload from(ServerPlayer player, boolean openScreen) {
        PlayerCommandData data = CommandGroupAttachments.get(player);
        List<SquadView> squads = new ArrayList<>();
        for (String id : data.squadIds()) {
            squads.add(new SquadView(id, data.squadName(id), data.color(id),
                    data.strategy(id).name(), (int) data.memberCount(id)));
        }

        List<MemberView> members = new ArrayList<>();
        data.memberSquadsView().forEach((uuidText, squadId) -> {
            try {
                UUID uuid = UUID.fromString(uuidText);
                NpcBase loaded = CommandGroupActionPacket.findOwnedNpc(player, uuid);
                int entityId = loaded != null && loaded.level() == player.level() ? loaded.getId() : -1;
                String name = loaded != null ? loaded.getNpcName() : data.memberName(uuid);
                CommandRank rank = data.rankOf(uuid);
                members.add(new MemberView(uuidText, entityId, name, squadId, rank.name(),
                        data.color(squadId), data.isSelected(uuid)));
            } catch (IllegalArgumentException ignored) {}
        });
        members.sort((a, b) -> {
            int squadCompare = a.squadId.compareTo(b.squadId);
            if (squadCompare != 0) return squadCompare;
            return Integer.compare(CommandRank.byName(b.rank).priority(), CommandRank.byName(a.rank).priority());
        });
        return new CommandGroupSyncPayload(openScreen, data.activeSquadId(), squads, members,
                data.corner1(), data.corner2());
    }


    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int length = Math.max(0, Math.min(32767, buf.readInt()));
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writePos(ByteBuf buf, BlockPos pos) {
        buf.writeBoolean(pos != null);
        if (pos != null) buf.writeLong(pos.asLong());
    }

    private static BlockPos readPos(ByteBuf buf) {
        return buf.readBoolean() ? BlockPos.of(buf.readLong()) : null;
    }
}
