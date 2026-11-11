package com.jgeted.sagadyssey.core.network;

import com.jgeted.sagadyssey.core.LegacyFeatureFlags;
import com.jgeted.sagadyssey.npc.faction.network.FactionDataSyncPayload;
import com.jgeted.sagadyssey.npc.faction.network.FactionStandingsUpdatePayload;
import com.jgeted.sagadyssey.npc.faction.network.RequestStandingsRefreshPacket;
import com.jgeted.sagadyssey.npc.command.network.CommandGroupActionPacket;
import com.jgeted.sagadyssey.npc.command.network.CommandGroupSyncPayload;
import com.jgeted.sagadyssey.npc.network.NpcFarmModePacket;
import com.jgeted.sagadyssey.npc.network.NpcInteractionPacket;
import com.jgeted.sagadyssey.npc.network.NpcProfessionPacket;
import com.jgeted.sagadyssey.npc.network.NpcViewSnapshotPayload;
import com.jgeted.sagadyssey.npc.network.NpcHandoverIntentPacket;
import com.jgeted.sagadyssey.npc.network.HandoverPromptPayload;
import com.jgeted.sagadyssey.npc.network.OpenNpcTradePacket;
import com.jgeted.sagadyssey.npc.network.SubmitTradeProposalPacket;
import com.jgeted.sagadyssey.npc.network.TradeSessionPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Sagadyssey 网络通信注册中心。
 * 所有数据包类型在这里统一注册。
 */
public class SagadysseyNetworking {
    private static final String PROTOCOL_VERSION = "13";

    @SubscribeEvent
    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        if (LegacyFeatureFlags.LEGACY_RESEARCH_ENABLED) {
            // 旧研究系统网络协议保留在源码中，但停用时不暴露运行时入口。
            registrar.playToServer(
                    ResearchUnlockPacket.TYPE,
                    ResearchUnlockPacket.STREAM_CODEC,
                    ResearchUnlockPacket::handle
            );
            registrar.playToClient(
                    ResearchSyncPayload.TYPE,
                    ResearchSyncPayload.STREAM_CODEC
            );
        }

        // 客户端→服务端：聊天消息（测试用）
        registrar.playToServer(
                ChatPayload.TYPE,
                ChatPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)
                            || !NetworkRateLimiter.allow(player, "test_chat", 20)) return;
                    var message = net.minecraft.network.chat.Component.literal(
                            "§6[Sagadyssey]§r " + payload.message());
                    player.sendSystemMessage(message);
                })
        );

        // 客户端→服务端：NPC 交互
        registrar.playToServer(
                NpcInteractionPacket.TYPE,
                NpcInteractionPacket.STREAM_CODEC,
                NpcInteractionPacket::handle
        );

        // 服务端→客户端：按观察者过滤的 NPC 只读投影
        registrar.playToClient(
                NpcViewSnapshotPayload.TYPE,
                NpcViewSnapshotPayload.STREAM_CODEC
        );

        registrar.playToServer(
                NpcHandoverIntentPacket.TYPE,
                NpcHandoverIntentPacket.STREAM_CODEC,
                NpcHandoverIntentPacket::handle
        );
        registrar.playToClient(
                HandoverPromptPayload.TYPE,
                HandoverPromptPayload.STREAM_CODEC
        );

        // 客户端→服务端：NPC 职业切换
        registrar.playToServer(
                NpcProfessionPacket.TYPE,
                NpcProfessionPacket.STREAM_CODEC,
                NpcProfessionPacket::handle
        );

        // 客户端→服务端：农民务农模式切换
        registrar.playToServer(
                NpcFarmModePacket.TYPE,
                NpcFarmModePacket.STREAM_CODEC,
                NpcFarmModePacket::handle
        );

        // v2 真实库存交易：稳定会话、明确提案、观察者安全投影。
        registrar.playToServer(
                OpenNpcTradePacket.TYPE,
                OpenNpcTradePacket.STREAM_CODEC,
                OpenNpcTradePacket::handle
        );
        registrar.playToServer(
                SubmitTradeProposalPacket.TYPE,
                SubmitTradeProposalPacket.STREAM_CODEC,
                SubmitTradeProposalPacket::handle
        );
        registrar.playToClient(
                TradeSessionPayload.TYPE,
                TradeSessionPayload.STREAM_CODEC
        );

        // === 阵营声望网络包 ===

        // 服务端→客户端：全量声望同步
        registrar.playToClient(
                FactionDataSyncPayload.TYPE,
                FactionDataSyncPayload.STREAM_CODEC
        );

        // 服务端→客户端：增量声望更新
        registrar.playToClient(
                FactionStandingsUpdatePayload.TYPE,
                FactionStandingsUpdatePayload.STREAM_CODEC
        );

        // 客户端→服务端：GUI 数据保鲜请求
        registrar.playToServer(
                RequestStandingsRefreshPacket.TYPE,
                RequestStandingsRefreshPacket.STREAM_CODEC,
                RequestStandingsRefreshPacket::handle
        );

        // === 指挥杖集团管理 ===
        registrar.playToServer(
                CommandGroupActionPacket.TYPE,
                CommandGroupActionPacket.STREAM_CODEC,
                CommandGroupActionPacket::handle
        );
        registrar.playToClient(
                CommandGroupSyncPayload.TYPE,
                CommandGroupSyncPayload.STREAM_CODEC
        );
    }
}
