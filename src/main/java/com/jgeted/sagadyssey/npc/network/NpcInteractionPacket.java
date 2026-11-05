package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.core.network.NetworkRateLimiter;
import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.faction.NpcFaction;
import com.jgeted.sagadyssey.npc.identity.NpcDialogue;
import com.jgeted.sagadyssey.npc.identity.NpcDialogueContext;
import com.jgeted.sagadyssey.npc.identity.NpcIdentityRelations;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.service.AuthorityScope;
import com.jgeted.sagadyssey.social.service.ServiceAuthorityVerdict;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.scenario.ScenarioLabController;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.ExpectedRevision;
import com.jgeted.sagadyssey.worldsim.transfer.TransferEndpoint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端→服务端：NPC 交互请求。
 * action:
 *   "request_stats" — 请求 NPC 属性数据（右键 NPC 时发送）
 *   "recruit"        — 确认招募（在招募界面点击 Hire 按钮）
 *   "follow"         — 命令 NPC 跟随
 *   "stay"           — 命令 NPC 原地待命
 *   "work"           — 命令 NPC 开始工作（务农/伐木挖矿，按职业）
 */
public record NpcInteractionPacket(int npcId, String action) implements CustomPacketPayload {
    private static int expectedStatsNpcId = -1;
    private static long expectedStatsUntilNanos;

    public static final Type<NpcInteractionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_interact")
    );

    public static final StreamCodec<ByteBuf, NpcInteractionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeInt(packet.npcId);
                NetworkStrings.write(buf, packet.action, 32);
            },
            buf -> new NpcInteractionPacket(buf.readInt(), NetworkStrings.read(buf, 32))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端显式交互入口；回应只有在短时间内匹配该实体时才允许打开界面。 */
    public static void requestStats(int npcId) {
        expectedStatsNpcId = npcId;
        expectedStatsUntilNanos = System.nanoTime() + 3_000_000_000L;
        PacketDistributor.sendToServer(new NpcInteractionPacket(npcId, "request_stats"));
    }

    public static boolean consumeExpectedStatsResponse(int npcId) {
        boolean expected = expectedStatsNpcId == npcId && System.nanoTime() <= expectedStatsUntilNanos;
        expectedStatsNpcId = -1;
        expectedStatsUntilNanos = 0L;
        return expected;
    }

    /**
     * 服务端处理入口。
     */
    public static void handle(final NpcInteractionPacket data, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (!NetworkRateLimiter.allow(player, "npc_interaction", 1)) return;
            Entity entity = player.serverLevel().getEntity(data.npcId);

            // NPC 不存在或已消失
            if (!(entity instanceof NpcBase npc)) {
                player.displayClientMessage(
                        Component.literal("§c这个 NPC 已经不存在了"), true);
                return;
            }
            if (!npc.isAlive() || player.distanceToSqr(npc) > 64.0D) {
                player.displayClientMessage(Component.literal("§c你离 NPC 太远了"), true);
                return;
            }

            switch (data.action) {
                case "request_stats" -> handleRequestStats(player, npc);
                case "talk" -> handleTalk(player, npc);
                case "recruit" -> handleRecruit(player, npc);
                case "follow" -> handleCommand(player, npc, "follow");
                case "stay" -> handleCommand(player, npc, "stay");
                case "work" -> handleCommand(player, npc, "work");
                case "open_equip" -> handleOpenEquip(player, npc);
                case "unbind_mount" -> handleUnbindMount(player, npc);
                case "request_mounts" -> handleRequestMounts(player, npc);
                case "tether_mount" -> handleTetherMount(player, npc);
                case "lead_mount" -> handleLeadMount(player, npc);
                case "mount_up" -> handleMountUp(player, npc);
                case "untether_mount" -> handleUntetherMount(player, npc);
                case "dismiss" -> handleDismiss(player, npc);
                default -> player.displayClientMessage(
                        Component.literal("§e未知操作：" + data.action), true);
            }
        });
    }

    /**
     * 处理 stats 请求：收集 NPC 属性，发回客户端。
     */
    private static void handleRequestStats(ServerPlayer player, NpcBase npc) {
        if (!npc.isAlive() || player.distanceToSqr(npc) > 64.0D) {
            player.displayClientMessage(Component.literal("§c你离 NPC 太远了"), true);
            return;
        }
        npc.playAction(NpcIdentityRelations.greetingAction(npc, player), 30);
        Component greeting = NpcDialogue.line(npc, NpcDialogueContext.GREETING, player);
        player.sendSystemMessage(greeting);
        NpcViewSnapshotPayload.openFor(player, npc, greeting.getString());
    }

    /** Keeps the context screen open while delivering an actual server-authoritative reply. */
    private static void handleTalk(ServerPlayer player, NpcBase npc) {
        npc.playAction(NpcIdentityRelations.greetingAction(npc, player), 30);
        Component reply = NpcDialogue.line(npc, NpcDialogueContext.CONVERSATION, player);
        player.sendSystemMessage(reply);
        NpcViewSnapshotPayload.openFor(player, npc, reply.getString());
    }

    /**
     * 处理招募请求：检查费用、扣款、设主人。
     */
    private static void handleRecruit(ServerPlayer player, NpcBase npc) {
        // 距离检查：玩家离 NPC 太远不行
        if (player.distanceToSqr(npc) > 36.0D) { // 6 格以内
            player.displayClientMessage(
                    Component.literal("§c你离 NPC 太远了"), true);
            return;
        }

        if (!npc.isSociallyRecruitable()) {
            npc.playSocialGreeting();
            player.displayClientMessage(Component.translatable(
                    "dialogue.sagadyssey.recruitment.closed",
                    npc.getIdentityTitle()), false);
            return;
        }

        if (ScenarioLabController.isLabNpc(player.getServer(), npc.getUUID())) {
            player.displayClientMessage(Component.literal("§e这是实验场固定角色，会保留原有岗位；"
                    + "你仍可与其交谈、交易并观察工作。"), false);
            return;
        }

        UUID playerCharacterId = CharacterRegistry.currentId(player);
        if (!player.isAlive() || playerCharacterId == null) {
            player.sendSystemMessage(Component.literal("当前没有可用的在生人物，不能招募。"));
            return;
        }
        ServiceRelationshipData services = ServiceRelationshipData.get(player.getServer());
        if (!services.activeForAgent(npc.getCharacterId(), player.serverLevel().getGameTime()).isEmpty()
                && !services.hasActiveBetween(playerCharacterId, npc.getCharacterId(),
                player.serverLevel().getGameTime())) {
            player.displayClientMessage(
                    Component.literal("§c这个 NPC 正在履行另一份服务关系"), true);
            return;
        }

        if (services.hasActiveBetween(playerCharacterId, npc.getCharacterId(),
                player.serverLevel().getGameTime())) {
            player.displayClientMessage(
                    Component.literal("§e你们之间已经有生效中的服务关系"), true);
            return;
        }

        int cost = npc.getAdjustedRecruitmentCost();
        var faction = npc.getFaction();
        boolean isHostileFaction = faction != null && faction.canBeHostile();
        if (isHostileFaction) {
            cost *= 2;
        }

        // 检查玩家有没有足够绿宝石
        if (!hasEnoughEmeralds(player, cost)) {
            String factionTag = isHostileFaction ? "§c（敌对招募费用翻倍）" : "";
            player.displayClientMessage(
                    Component.literal("§c绿宝石不足！需要 " + cost + " 个绿宝石" + factionTag), true);
            return;
        }

        if (!paySigningFee(player, npc, playerCharacterId, cost)) {
            player.displayClientMessage(
                    Component.literal("§c签约礼未能转入 NPC 的真实背包；请检查库存与空间"), true);
            return;
        }

        // 旧字段只供旧客户端显示；权威来自 onRecruited 创建的服务关系。
        npc.setOwner(player);
        NpcLifeData.get(player.getServer()).onRecruited(npc,
                playerCharacterId, player.serverLevel().getGameTime());
        NpcLifeData.get(player.getServer()).remember(npc.getCharacterId(),
                new com.jgeted.sagadyssey.social.life.NpcMemoryRecord("recruitment",
                        playerCharacterId, "与 " + player.getName().getString() + " 建立有限服务关系",
                        35, 55, player.serverLevel().getGameTime(),
                        player.serverLevel().getGameTime() + 96000L),
                player.serverLevel().getGameTime());
        CommandGroupAttachments.registerNpc(player, npc);

        // 通知玩家
        player.displayClientMessage(
                Component.literal("§a已与 " + npc.getNpcName() + " 建立服务关系；"
                        + cost + " 枚绿宝石已作为签约礼进入其背包"), false);
    }

    /**
     * 检查玩家背包中是否有足够的绿宝石。
     */
    private static boolean hasEnoughEmeralds(ServerPlayer player, int cost) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.EMERALD)) {
                count += stack.getCount();
                if (count >= cost) return true;
            }
        }
        return false;
    }

    private static boolean paySigningFee(ServerPlayer player, NpcBase npc,
                                         UUID playerCharacterId, int cost) {
        RightsStateData rights = RightsStateData.get(player.getServer());
        SubjectRef payer = SubjectRef.character(playerCharacterId);
        SubjectRef recipient = SubjectRef.character(npc.getCharacterId());
        UUID sourceId = ContainerIds.playerPersonal(playerCharacterId);
        UUID destinationId = ContainerIds.npcPersonal(npc.getCharacterId());
        rights.ensureContext(sourceId, "player:" + playerCharacterId + ":personal",
                payer, payer, AccessPolicy.PRIVATE);
        rights.ensureContext(destinationId, "npc:" + npc.getCharacterId() + ":personal",
                recipient, recipient, AccessPolicy.PRIVATE);
        long now = player.serverLevel().getGameTime();
        TransferRequest request = new TransferRequest(ContainerIds.named("signing_fee:"
                + playerCharacterId + ':' + npc.getCharacterId() + ':' + now), payer,
                TransferIntent.PAYMENT,
                List.of(new TransferLeg(sourceId, destinationId,
                        ItemFingerprint.of(new ItemStack(Items.EMERALD)), cost)),
                List.of(new ExpectedRevision(sourceId, rights.context(sourceId).revision()),
                        new ExpectedRevision(destinationId, rights.context(destinationId).revision())),
                "voluntary_employment_signing_gift", now);
        return new TransferService(rights, TransferLedgerData.get(player.getServer()))
                .submit(request, Map.of(sourceId, new TransferEndpoint(sourceId, player.getInventory()),
                        destinationId, new TransferEndpoint(destinationId,
                                npc.getEquipmentInventory())), now).successful();
    }

    /**
     * 处理命令请求：follow / stay。
     * 验证实体存在、归属关系，然后执行（TODO: 后续接入 AI 行为）。
     */
    private static void handleCommand(ServerPlayer player, NpcBase npc, String action) {
        String npcName = npc.getNpcName();

        AuthorityScope scope = switch (action) {
            case "follow" -> AuthorityScope.FOLLOW;
            case "stay" -> AuthorityScope.HOLD_POSITION;
            case "work" -> AuthorityScope.WORK;
            default -> null;
        };
        if (scope == null) return;
        if (action.equals("work")) {
            boolean canWork = switch (npc.getProfession()) {
                case WORKER, BLACKSMITH, FARMER, BARD, MEDIC, TRADER, COURIER -> true;
                default -> false;
            };
            if (!canWork) {player.displayClientMessage(Component.literal("§e这个职业不会工作"), true);return;}
        }
        NpcCommand requested = switch(action) {case "follow" -> NpcCommand.FOLLOW;case "stay" -> NpcCommand.STAY;default -> NpcCommand.WORK;};
        var result = com.jgeted.sagadyssey.npc.brain.command.NpcCommandService.issue(player,npc,requested);
        player.displayClientMessage(Component.literal((result.accepted()?"§a":"§c")+result.detail()),true);
        if (!result.accepted()) return;

        switch (action) {
            case "follow" -> {
                player.displayClientMessage(
                        Component.literal("§a已登记 " + npcName + " 的随行命令"), true);
                player.sendSystemMessage(NpcDialogue.line(npc, NpcDialogueContext.COMMAND, player));
            }
            case "stay" -> {
                player.displayClientMessage(
                        Component.literal("§a已登记 " + npcName + " 的驻守命令"), true);
                player.sendSystemMessage(NpcDialogue.line(npc, NpcDialogueContext.COMMAND, player));
            }
            case "work" -> {
                boolean canWork = switch (npc.getProfession()) {
                    case WORKER, BLACKSMITH, FARMER, BARD, MEDIC, TRADER, COURIER -> true;
                    default -> false;
                };
                if (!canWork) {
                    player.displayClientMessage(Component.literal("§e这个职业不会工作"), true);
                    return;
                }
                if (npc.getProfession() == NpcProfession.TRADER
                        || npc.getProfession() == NpcProfession.COURIER) {
                    player.displayClientMessage(Component.literal("§a已命令 " + npcName
                            + " 的物流命令。可用 /saga logistics orders 查看队列"), true);
                } else {
                    player.displayClientMessage(Component.literal("§a已登记 " + npcName + " 的工作命令"), true);
                }
                player.sendSystemMessage(NpcDialogue.line(npc, NpcDialogueContext.COMMAND, player));
            }
        }
    }

    /**
     * 处理打开装备界面请求。
     */
    private static void handleOpenEquip(ServerPlayer player, NpcBase npc) {
        if (player.distanceToSqr(npc) > 64.0D) {
            player.displayClientMessage(Component.literal("§c你离 NPC 太远了"), true);
            return;
        }
        if (!requireAuthority(player, npc, AuthorityScope.MANAGE_ISSUED_EQUIPMENT, false)) return;
        player.displayClientMessage(Component.literal("§e服务关系不授予查看或拿取私人背包的权利。"
                + "配发装备将通过部分可见界面与直接递交完成。"), true);
    }

    /**
     * 处理解除坐骑绑定。
     */
    private static void handleUnbindMount(ServerPlayer player, NpcBase npc) {
        if (!requireAuthority(player, npc, AuthorityScope.OPERATE_MOUNT, false)) return;
        npc.unbindMount();
        player.displayClientMessage(Component.literal("§a已解除坐骑绑定"), true);
    }

    /**
     * 处理请求周围可用坐骑列表。
     */
    private static void handleRequestMounts(ServerPlayer player, NpcBase npc) {
        if (!requireAuthority(player, npc, AuthorityScope.OPERATE_MOUNT, false)) return;
        var mounts = new java.util.ArrayList<String>();
        for (var horse : player.level().getEntitiesOfClass(AbstractHorse.class,
                npc.getBoundingBox().inflate(10))) {
            if (horse.isTamed() && horse.getOwnerUUID() != null
                    && horse.getOwnerUUID().equals(player.getUUID())) {
                mounts.add(String.format("%d|%s|%.0f/%.0f",
                        horse.getId(), horse.getName().getString(),
                        horse.getHealth(), horse.getMaxHealth()));
            }
        }
        if (mounts.isEmpty()) {
            player.displayClientMessage(Component.literal("§e附近没有可分配的坐骑"), false);
        } else {
            player.displayClientMessage(Component.literal("§a可用坐骑 (共" + mounts.size() + "匹):"), false);
            for (String m : mounts) {
                String[] parts = m.split("\\|");
                player.displayClientMessage(Component.literal(
                        String.format("  §f- %s (%s HP)  §7/saga mount bind %d %s",
                                parts[1], parts[2], npc.getId(), parts[0])), false);
            }
        }
    }

    private static void handleTetherMount(ServerPlayer player, NpcBase npc) {
        if (!requireAuthority(player, npc, AuthorityScope.OPERATE_MOUNT, false)) return;
        if (!npc.hasMount()) {
            player.displayClientMessage(Component.literal("§e没有绑定坐骑"), true);
            return;
        }
        if (npc.tetherHorse()) {
            if (npc.isPassenger()) npc.dismountToBind();
            npc.setLeadMountMode(true);
            player.displayClientMessage(Component.literal("§a已拴在附近栅栏上"), true);
        } else {
            player.displayClientMessage(Component.literal("§c附近没有栅栏或缺少栓绳"), true);
        }
    }

    private static void handleLeadMount(ServerPlayer player, NpcBase npc) {
        if (!requireAuthority(player, npc, AuthorityScope.OPERATE_MOUNT, false)) return;
        if (!npc.hasMount()) {
            player.displayClientMessage(Component.literal("§e没有绑定坐骑"), true);
            return;
        }
        npc.untetherHorse();
        if (npc.isPassenger()) npc.dismountToBind();
        // 把马拴到 NPC 身上，体现"牵马"视觉效果
        Entity mount = npc.getMount();
        if (mount instanceof Leashable leashable && !leashable.isLeashed()) {
            leashable.setLeashedTo(npc, true);
        }
        npc.setLeadMountMode(true);
        player.displayClientMessage(Component.literal("§a已下马，切换为牵马步行"), true);
    }

    /**
     * 处理上马：从牵马/拴马状态恢复骑行。
     * 不直接 startRiding，而是设置 pendingMount 标记，
     * 让 MountGoal 驱动 NPC 走向坐骑后再上马。
     */
    private static void handleMountUp(ServerPlayer player, NpcBase npc) {
        if (!requireAuthority(player, npc, AuthorityScope.OPERATE_MOUNT, false)) return;
        if (!npc.hasMount()) {
            player.displayClientMessage(Component.literal("§e没有绑定坐骑"), true);
            return;
        }
        Entity mount = npc.getMount();
        if (mount == null || !mount.isAlive()) {
            player.displayClientMessage(Component.literal("§c坐骑已死亡或丢失"), true);
            return;
        }
        // 解拴，如果是栅栏栓绳则回收（拴栅栏时消耗了一根）
        if (mount instanceof Leashable leashable && leashable.isLeashed()) {
            if (leashable.getLeashHolder() instanceof LeashFenceKnotEntity) {
                npc.addLeadToInventory();
            }
            leashable.dropLeash(true, false);
        }
        // 如果 NPC 已经是乘客，先下马（防止重复）
        if (npc.isPassenger()) {
            npc.stopRiding();
        }
        npc.setLeadMountMode(false);
        // 设置标记，让 MountGoal 接管导航和上马
        npc.setPendingMount();
        player.displayClientMessage(Component.literal("§aNPC 正走向坐骑…"), true);
    }

    /**
     * 处理解除拴马：把马从栅栏上解下来，回收栓绳。
     */
    private static void handleUntetherMount(ServerPlayer player, NpcBase npc) {
        if (!requireAuthority(player, npc, AuthorityScope.OPERATE_MOUNT, false)) return;
        if (!npc.hasMount()) {
            player.displayClientMessage(Component.literal("§e没有绑定坐骑"), true);
            return;
        }
        npc.untetherHorse();
        npc.addLeadToInventory(); // 拴栅栏时消耗了一根，这里回收
        npc.setLeadMountMode(false);
        player.displayClientMessage(Component.literal("§a已解除拴马，栓绳已放回背包"), true);
    }

    /**
     * 处理解散：将已招募 NPC 恢复原阵营、清除主人。
     */
    private static void handleDismiss(ServerPlayer player, NpcBase npc) {
        if (!requireAuthority(player, npc, AuthorityScope.END_SERVICE, false)) return;
        CommandGroupAttachments.unregisterNpc(player, npc);
        npc.dismiss();
        NpcLifeData.get(player.getServer()).onDismissed(npc, player.serverLevel().getGameTime());
        player.displayClientMessage(
                Component.literal("§a服务关系已解除；NPC 的家庭、私人财产和社会身份保持不变"), true);
    }

    private static boolean requireAuthority(ServerPlayer player, NpcBase npc,
                                            AuthorityScope scope, boolean dangerous) {
        UUID principal = CharacterRegistry.currentId(player);
        if (!player.isAlive() || principal == null) {
            player.sendSystemMessage(Component.literal("当前没有可用的在生人物，不能行使授权。"));
            return false;
        }
        ServiceAuthorityVerdict verdict = ServiceRelationshipData.get(player.getServer())
                .authorize(principal, npc.getCharacterId(), scope, dangerous,
                        player.serverLevel().getGameTime());
        if (verdict.allowed()) return true;
        player.displayClientMessage(Component.literal((verdict.status()
                == ServiceAuthorityVerdict.Status.REFUSED ? "§eNPC 拒绝：" : "§c没有授权：")
                + verdict.reason()), true);
        return false;
    }
}
