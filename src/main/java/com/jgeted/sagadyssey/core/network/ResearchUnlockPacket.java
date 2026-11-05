package com.jgeted.sagadyssey.core.network;

import com.jgeted.sagadyssey.core.LegacyFeatureFlags;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.research.ResearchAttachments;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 研究解锁数据包。
 * 客户端点击"解锁"按钮后发送给服务端，服务端处理并返回结果。
 */
public record ResearchUnlockPacket(String researchId) implements CustomPacketPayload {

    public static final Type<ResearchUnlockPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "research_unlock")
    );

    public static final StreamCodec<ByteBuf, ResearchUnlockPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                NetworkStrings.write(buf, packet.researchId, 128);
            },
            buf -> new ResearchUnlockPacket(NetworkStrings.read(buf, 128))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 服务端处理：收到客户端的解锁请求后执行。
     */
    public static void handle(final ResearchUnlockPacket data, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!LegacyFeatureFlags.LEGACY_RESEARCH_ENABLED) return;
            var player = context.player();
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && !NetworkRateLimiter.allow(serverPlayer, "research_unlock", 2)) return;
            boolean success = ResearchAttachments.tryUnlock(player, data.researchId());
            if (success) {
                player.displayClientMessage(
                        Component.literal("研究解锁成功！"), false);
            } else {
                player.displayClientMessage(
                        Component.literal("解锁失败——检查前置或点数"), false);
            }
        });
    }
}
