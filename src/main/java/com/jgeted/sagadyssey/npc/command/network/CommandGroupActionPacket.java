package com.jgeted.sagadyssey.npc.command.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.core.network.NetworkRateLimiter;
import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.npc.command.PlayerCommandData;
import com.jgeted.sagadyssey.npc.command.SquadStrategy;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.service.AuthorityScope;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.registry.ModItems;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 客户端发送的集团管理操作；所有成员和物品权限都在服务端复核。 */
public record CommandGroupActionPacket(String action) implements CustomPacketPayload {
    public static final Type<CommandGroupActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "command_group_action"));

    public static final StreamCodec<ByteBuf, CommandGroupActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> writeString(buf, packet.action),
            buf -> new CommandGroupActionPacket(readString(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(CommandGroupActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!NetworkRateLimiter.allow(player, "command_group", 2)) return;
            if (!player.getMainHandItem().is(ModItems.NPC_COMMAND_WAND.get())
                    && !player.getOffhandItem().is(ModItems.NPC_COMMAND_WAND.get())) {
                player.displayClientMessage(Component.literal("§c需要手持指挥杖"), true);
                return;
            }

            PlayerCommandData data = CommandGroupAttachments.get(player);
            boolean open = "open".equals(packet.action);
            switch (packet.action) {
                case "open" -> {}
                case "cycle_squad" -> data.cycleActiveSquad();
                case "create_squad" -> {
                    if (data.squadIds().size() >= 16) {
                        player.displayClientMessage(Component.literal("§e小队已达上限（16）"), true);
                    } else {
                        String id = data.createSquadFromSelection();
                        player.displayClientMessage(Component.literal("§a已拆分为 " + data.squadName(id)), true);
                    }
                }
                case "assign_selected" -> {
                    data.assignSelectionToActive();
                    player.displayClientMessage(Component.literal("§a选中成员已加入 " + data.activeSquadName()), true);
                }
                case "merge_main" -> {
                    if (data.mergeActiveIntoMain()) {
                        player.displayClientMessage(Component.literal("§a小队已组合回主力队"), true);
                    }
                }
                case "select_squad" -> data.selectActiveSquad();
                case "rank_up" -> data.changeSelectedRank(true);
                case "rank_down" -> data.changeSelectedRank(false);
                case "cycle_strategy" -> {
                    data.cycleActiveStrategy();
                    applyActiveStrategyImmediately(player, data);
                    player.displayClientMessage(Component.literal("§e战术：" + data.activeStrategy().displayName()), true);
                }
                case "command_follow" -> commandSelected(player, data, NpcCommand.FOLLOW);
                case "command_stay" -> commandSelected(player, data, NpcCommand.STAY);
                case "command_work" -> commandSelected(player, data, NpcCommand.WORK);
                case "set_work_zone" -> applyWorkZone(player, data, false);
                case "clear_work_zone" -> applyWorkZone(player, data, true);
                case "clear_selection" -> data.clearSelectedMembers();
                default -> {
                    player.displayClientMessage(Component.literal("§c未知集团操作"), true);
                    return;
                }
            }
            CommandGroupAttachments.sync(player, open);
        });
    }

    private static void commandSelected(ServerPlayer player, PlayerCommandData data, NpcCommand command) {
        int changed = 0;
        for (UUID id : data.selectedMembers()) {
            NpcBase npc = findOwnedNpc(player, id);
            if (npc == null) continue;
            if (command == NpcCommand.WORK && !supportsWorkCommand(npc.getProfession())) continue;
            AuthorityScope scope = switch (command) {
                case FOLLOW -> AuthorityScope.FOLLOW;
                case STAY -> AuthorityScope.HOLD_POSITION;
                case WORK -> AuthorityScope.WORK;
                default -> null;
            };
            if (scope == null || !authorized(player, npc, scope, false)) continue;
            if (!com.jgeted.sagadyssey.npc.brain.command.NpcCommandService.issue(player,npc,command).accepted()) continue;
            changed++;
        }
        player.displayClientMessage(Component.literal("§a已向 " + changed + " 名成员下达“"
                + commandLabel(command) + "”"), true);
    }

    private static void applyWorkZone(ServerPlayer player, PlayerCommandData data, boolean clear) {
        if (!clear && (data.corner1() == null || data.corner2() == null)) {
            player.displayClientMessage(Component.literal("§e请先用指挥杖在地面标记两个角"), true);
            return;
        }
        int changed = 0;
        for (UUID id : data.selectedMembers()) {
            NpcBase npc = findOwnedNpc(player, id);
            if (npc == null || (npc.getProfession() != NpcProfession.FARMER
                    && npc.getProfession() != NpcProfession.WORKER)) continue;
            if (!authorized(player, npc, AuthorityScope.ASSIGN_WORKPLACE, false)) continue;
            if (clear) npc.clearWorkZone();
            else npc.setWorkZone(data.corner1(), data.corner2());
            changed++;
        }
        player.displayClientMessage(Component.literal(clear
                ? "§a已清除 " + changed + " 名工作成员的区域"
                : "§a已把彩色区域分配给 " + changed + " 名工作成员"), true);
    }

    private static void applyActiveStrategyImmediately(ServerPlayer player, PlayerCommandData data) {
        SquadStrategy strategy = data.activeStrategy();
        for (var entry : data.memberSquadsView().entrySet()) {
            if (!data.activeSquadId().equals(entry.getValue())) continue;
            try {
                NpcBase npc = findOwnedNpc(player, UUID.fromString(entry.getKey()));
                if (npc == null) continue;
                if (!authorized(player, npc, AuthorityScope.DEFEND_PRINCIPAL,
                        strategy != SquadStrategy.PROTECT_OWNER
                                && strategy != SquadStrategy.HOLD_POSITION
                                && strategy != SquadStrategy.RETREAT)) continue;
                if (strategy == SquadStrategy.PROTECT_OWNER) {
                    npc.clearOwnerCombatTargets();
                    npc.setTarget(null);
                } else if (strategy == SquadStrategy.HOLD_POSITION) {
                    npc.clearOwnerCombatTargets();
                    npc.setTarget(null);
                    com.jgeted.sagadyssey.npc.brain.command.NpcCommandService.issue(player,npc,NpcCommand.STAY);
                } else if (strategy == SquadStrategy.RETREAT) {
                    npc.clearOwnerCombatTargets();
                    npc.setTarget(null);
                    com.jgeted.sagadyssey.npc.brain.command.NpcCommandService.issue(player,npc,NpcCommand.FOLLOW);
                }
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static String commandLabel(NpcCommand command) {
        return switch (command) {
            case FOLLOW -> "跟随";
            case STAY -> "待命";
            case WORK -> "工作";
            default -> "空闲";
        };
    }

    private static boolean supportsWorkCommand(NpcProfession profession) {
        return profession == NpcProfession.FARMER || profession == NpcProfession.WORKER
                || profession == NpcProfession.BLACKSMITH || profession == NpcProfession.BARD
                || profession == NpcProfession.MEDIC || profession == NpcProfession.TRADER
                || profession == NpcProfession.COURIER;
    }

    @Nullable
    public static NpcBase findOwnedNpc(ServerPlayer player, UUID characterId) {
        NpcBase npc = CharacterRegistryData.get(player.server)
                .findLoadedNpc(player.server, characterId);
        return npc != null && npc.isAlive() && npc.isOwnedBy(player.getUUID()) ? npc : null;
    }

    public static List<NpcBase> selectedLoadedNpcs(ServerPlayer player, PlayerCommandData data) {
        List<NpcBase> result = new ArrayList<>();
        for (UUID id : data.selectedMembers()) {
            NpcBase npc = findOwnedNpc(player, id);
            if (npc != null) result.add(npc);
        }
        return result;
    }

    private static boolean authorized(ServerPlayer player, NpcBase npc, AuthorityScope scope,
                                      boolean dangerous) {
        UUID principal = CharacterRegistry.currentId(player);
        if (!player.isAlive() || principal == null) return false;
        return ServiceRelationshipData.get(player.server).authorize(principal,
                npc.getCharacterId(), scope, dangerous,
                player.serverLevel().getGameTime()).allowed();
    }

    private static void writeString(ByteBuf buf, String text) {
        NetworkStrings.write(buf, text, 64);
    }

    private static String readString(ByteBuf buf) {
        return NetworkStrings.read(buf, 64);
    }
}
