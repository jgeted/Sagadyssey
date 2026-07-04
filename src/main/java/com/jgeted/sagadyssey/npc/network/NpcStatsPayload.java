package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.faction.NpcFaction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.gui.NpcCommandScreen;
import com.jgeted.sagadyssey.npc.gui.NpcRecruitScreen;
import com.jgeted.sagadyssey.npc.trade.NpcTradeOffer;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：NPC 完整属性数据（含交易列表）。
 */
public record NpcStatsPayload(
        int npcId,
        String npcName,
        String professionName,
        float currentHp,
        float maxHp,
        float attackDamage,
        float speed,
        float armor,
        int npcLevel,
        int experience,
        int kills,
        int moral,
        int recruitmentCost,
        boolean isOwned,
        String commandName,
        String factionName,
        List<byte[]> rawTrades
) implements CustomPacketPayload {

    public static final Type<NpcStatsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_stats")
    );

    public static final StreamCodec<ByteBuf, NpcStatsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeInt(packet.npcId);
                writeString(buf, packet.npcName);
                writeString(buf, packet.professionName);
                buf.writeFloat(packet.currentHp);
                buf.writeFloat(packet.maxHp);
                buf.writeFloat(packet.attackDamage);
                buf.writeFloat(packet.speed);
                buf.writeFloat(packet.armor);
                buf.writeInt(packet.npcLevel);
                buf.writeInt(packet.experience);
                buf.writeInt(packet.kills);
                buf.writeInt(packet.moral);
                buf.writeInt(packet.recruitmentCost);
                buf.writeBoolean(packet.isOwned);
                buf.writeByte(NpcCommand.valueOf(packet.commandName()).ordinal());
                writeString(buf, packet.factionName());
                // 交易数据：每个交易 = 完整 ItemStack 字节序列
                buf.writeInt(packet.rawTrades.size());
                for (byte[] t : packet.rawTrades) {
                    buf.writeInt(t.length);
                    buf.writeBytes(t);
                }
            },
            buf -> {
                int npcId = buf.readInt();
                String npcName = readString(buf);
                String profName = readString(buf);
                float curHp = buf.readFloat();
                float maxHp = buf.readFloat();
                float atk = buf.readFloat();
                float spd = buf.readFloat();
                float arm = buf.readFloat();
                int lvl = buf.readInt();
                int exp = buf.readInt();
                int kills = buf.readInt();
                int moral = buf.readInt();
                int cost = buf.readInt();
                boolean owned = buf.readBoolean();
                String cmd = NpcCommand.values()[buf.readByte()].name();
                String factionName = readString(buf);
                int tradeCount = buf.readInt();
                List<byte[]> trades = new ArrayList<>();
                for (int i = 0; i < tradeCount; i++) {
                    int len = buf.readInt();
                    byte[] data = new byte[len];
                    buf.readBytes(data);
                    trades.add(data);
                }
                return new NpcStatsPayload(npcId, npcName, profName, curHp, maxHp, atk, spd, arm,
                        lvl, exp, kills, moral, cost, owned, cmd, factionName, trades);
            }
    );

    /** 将 rawTrades 还原为 NpcTradeOffer 列表 */
    public List<NpcTradeOffer> buildTrades() {
        List<NpcTradeOffer> result = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return result;
        for (byte[] t : rawTrades) {
            io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(t);
            net.minecraft.network.RegistryFriendlyByteBuf fbuf =
                    new net.minecraft.network.RegistryFriendlyByteBuf(buf, mc.level.registryAccess());
            ItemStack costItem = ItemStack.OPTIONAL_STREAM_CODEC.decode(fbuf);
            int costMin = fbuf.readInt();
            int costMax = fbuf.readInt();
            ItemStack resultItem = ItemStack.OPTIONAL_STREAM_CODEC.decode(fbuf);
            int resultMin = fbuf.readInt();
            int resultMax = fbuf.readInt();
            int minNpcLevel = fbuf.readInt();
            if (costItem.isEmpty() || resultItem.isEmpty()) continue;
            result.add(new NpcTradeOffer(costItem, costMin, costMax,
                    resultItem, resultMin, resultMax, minNpcLevel));
        }
        return result;
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes();
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final NpcStatsPayload data, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (data.isOwned) {
                mc.setScreen(new NpcCommandScreen(data));
            } else {
                mc.setScreen(new NpcRecruitScreen(data));
            }
        });
    }

    public static NpcStatsPayload from(NpcBase npc) {
        int cost = npc.getRecruitmentCost();
        if (npc.getFaction() == NpcFaction.HOSTILE) {
            cost *= 2;
        }
        // 序列化交易数据（完整 ItemStack，含附魔）
        List<byte[]> raw = new ArrayList<>();
        for (NpcTradeOffer t : npc.getActiveTrades()) {
            io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
            net.minecraft.network.RegistryFriendlyByteBuf fbuf =
                    new net.minecraft.network.RegistryFriendlyByteBuf(buf, npc.level().registryAccess());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(fbuf, t.costItem());
            fbuf.writeInt(t.costMin());
            fbuf.writeInt(t.costMax());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(fbuf, t.resultItem());
            fbuf.writeInt(t.resultMin());
            fbuf.writeInt(t.resultMax());
            fbuf.writeInt(t.minNpcLevel());
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            raw.add(bytes);
        }
        return new NpcStatsPayload(
                npc.getId(),
                npc.getNpcName(),
                npc.getProfession().getDisplayName(),
                npc.getCurrentHp(),
                npc.getMaxHp(),
                npc.getAttackDamage(),
                npc.getSpeed(),
                npc.getArmorValue(),
                npc.getNpcLevel(),
                npc.getExperience(),
                npc.getKills(),
                npc.getMoral(),
                cost,
                npc.isOwned(),
                npc.getCommand().name(),
                npc.getFaction().name(),
                raw
        );
    }
}
