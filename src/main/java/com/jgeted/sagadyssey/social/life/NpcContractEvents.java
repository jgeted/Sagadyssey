package com.jgeted.sagadyssey.social.life;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRecord;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.logistics.WarehouseContainers;
import com.jgeted.sagadyssey.social.logistics.WarehouseRecord;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
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
import com.jgeted.sagadyssey.worldsim.transfer.TransferReceipt;
import com.jgeted.sagadyssey.worldsim.transfer.TransferRequest;
import com.jgeted.sagadyssey.worldsim.transfer.TransferService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 从聚落实体仓库向 NPC 私人箱支付合同工资；不会凭空修改抽象余额。 */
public final class NpcContractEvents {
    private NpcContractEvents() { }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 200 != 0) return;
        long now = server.overworld().getGameTime();
        NpcLifeData data = NpcLifeData.get(server);
        for (NpcEmploymentContract contract : data.contracts()) {
            if (!contract.status().ongoing()) continue;
            if (contract.paymentDue(now)) processPayment(server, data, contract, now);
            NpcEmploymentContract latest = data.contract(contract.npcId());
            if (latest != null && latest.readyToComplete(now)
                    && data.completeContract(contract.npcId(), now)) {
                syncServiceProjection(server, data, contract.npcId(), now);
                notifyEmployer(server, latest, "§e" + npcName(server, contract.npcId())
                        + " 的外派合同已经期满并结清。", false);
                WorldHistoryData.get(server).append(now, "npc_contract_completed",
                        contract.npcId(), "合同期满", contract.settlementId().toString(), 18);
            }
        }
    }

    private static void processPayment(MinecraftServer server, NpcLifeData data,
                                       NpcEmploymentContract contract, long now) {
        PaymentAttempt attempt = transferPhysicalWage(server, data, contract, now);
        switch (attempt.result()) {
            case PAID -> {
                data.recordContractPayment(contract.npcId(), attempt.amount(), now);
                adjustMorale(server, contract.npcId(), 3);
                notifyEmployer(server, contract, "§a已支付 " + npcName(server, contract.npcId())
                        + " 工资 " + attempt.amount() + " 枚绿宝石。", false);
                WorldHistoryData.get(server).append(now, "npc_wage_paid", contract.npcId(),
                        attempt.amount() + " emeralds", contract.settlementId().toString(), 8);
            }
            case RECEIVER_UNAVAILABLE, TREASURY_UNLOADED ->
                    data.recordContractPaymentBlocked(contract.npcId(), attempt.reason(), now);
            case INSUFFICIENT_FUNDS -> {
                if (!contract.scheduledPaymentDue(now)) {
                    data.recordContractPaymentBlocked(contract.npcId(), attempt.reason(), now);
                    return;
                }
                boolean breached = data.recordMissedContractPayment(contract.npcId(), now);
                adjustMorale(server, contract.npcId(), breached ? -20 : -8);
                notifyEmployer(server, contract, (breached ? "§4" : "§c")
                        + npcName(server, contract.npcId()) + "：" + (breached
                        ? "连续欠薪，外派合同已经违约。" : "本期工资未能支付：" + attempt.reason()), true);
                WorldHistoryData.get(server).append(now,
                        breached ? "npc_contract_breached" : "npc_wage_missed",
                        contract.npcId(), attempt.reason(), contract.settlementId().toString(),
                        breached ? 65 : 30);
            }
        }
        syncServiceProjection(server, data, contract.npcId(), now);
    }

    private static void syncServiceProjection(MinecraftServer server, NpcLifeData data,
                                              UUID npcId, long now) {
        NpcEmploymentContract latest = data.contract(npcId);
        if (latest == null) return;
        ServiceRelationshipData.get(server).syncContract(latest, now);
        if (latest.status().ongoing()) return;
        NpcBase npc = CharacterRegistryData.get(server).findLoadedNpc(server, npcId);
        if (npc != null) npc.dismiss();
    }

    private static PaymentAttempt transferPhysicalWage(MinecraftServer server, NpcLifeData data,
                                                        NpcEmploymentContract contract, long now) {
        NpcLifeRecord life = data.record(contract.npcId());
        if (life == null || life.privateStoragePosition() == null) {
            return PaymentAttempt.blocked(PaymentResult.RECEIVER_UNAVAILABLE,
                    "NPC 没有私人收款箱；这不会记为雇主欠薪");
        }
        ServerLevel level = levelForLife(server, life);
        BlockPos storagePos = life.privateStoragePosition();
        if (level == null || !level.hasChunkAt(storagePos)) {
            return PaymentAttempt.blocked(PaymentResult.RECEIVER_UNAVAILABLE,
                    "NPC 私人收款箱所在区块未加载；等待后重试");
        }
        Container destination = WarehouseContainers.asContainer(level.getBlockEntity(storagePos));
        int amount = contract.amountDue(server.overworld().getGameTime());
        if (destination == null || emeraldCapacity(destination) < amount) {
            return PaymentAttempt.blocked(PaymentResult.RECEIVER_UNAVAILABLE,
                    destination == null ? "NPC 私人收款箱已失效" : "NPC 私人收款箱没有足够空间");
        }

        LogisticsData logistics = LogisticsData.get(server);
        List<TreasurySource> treasuries = new ArrayList<>();
        boolean hasRegisteredTreasury = false;
        int available = 0;
        for (WarehouseRecord warehouse : logistics.warehouses()) {
            if (!contract.settlementId().equals(warehouse.settlementId())
                    || !life.dimension().equals(warehouse.dimension())) continue;
            hasRegisteredTreasury = true;
            if (!level.hasChunkAt(warehouse.position())) continue;
            Container container = WarehouseContainers.asContainer(
                    level.getBlockEntity(warehouse.position()));
            if (container == null || container == destination) continue;
            int emeralds = countEmeralds(container);
            treasuries.add(new TreasurySource(warehouse, container, emeralds));
            available += emeralds;
        }
        if (hasRegisteredTreasury && treasuries.isEmpty()) {
            return PaymentAttempt.blocked(PaymentResult.TREASURY_UNLOADED,
                    "聚落工资仓库未加载；等待后重试");
        }
        if (available < amount) {
            return PaymentAttempt.blocked(PaymentResult.INSUFFICIENT_FUNDS,
                    "聚落实体仓库仅有 " + available + "/" + amount + " 枚绿宝石");
        }

        RightsStateData rights = RightsStateData.get(server);
        TransferLedgerData ledger = TransferLedgerData.get(server);
        SubjectRef settlement = SubjectRef.institution(contract.settlementId());
        SubjectRef recipient = SubjectRef.character(contract.npcId());
        UUID destinationId = ContainerIds.block("private_storage", life.dimension(), storagePos);
        rights.ensureContext(destinationId, life.dimension() + ':' + storagePos.asLong(),
                recipient, recipient, AccessPolicy.PRIVATE);

        Map<UUID, TransferEndpoint> endpoints = new LinkedHashMap<>();
        endpoints.put(destinationId, new TransferEndpoint(destinationId, destination));
        List<TransferLeg> legs = new ArrayList<>();
        List<ExpectedRevision> revisions = new ArrayList<>();
        revisions.add(new ExpectedRevision(destinationId, rights.context(destinationId).revision()));
        int remaining = amount;
        ItemFingerprint emerald = ItemFingerprint.of(new ItemStack(Items.EMERALD));
        for (TreasurySource treasury : treasuries) {
            if (remaining <= 0) break;
            int moved = Math.min(remaining, treasury.emeralds());
            if (moved <= 0) continue;
            UUID sourceId = treasury.record().id();
            rights.ensureContext(sourceId, treasury.record().dimension() + ':'
                            + treasury.record().position().asLong(), settlement, settlement,
                    AccessPolicy.INSTITUTION);
            endpoints.put(sourceId, new TransferEndpoint(sourceId, treasury.container()));
            revisions.add(new ExpectedRevision(sourceId, rights.context(sourceId).revision()));
            legs.add(new TransferLeg(sourceId, destinationId, emerald, moved));
            remaining -= moved;
        }
        UUID transferId = ContainerIds.named("wage:" + contract.npcId() + ':'
                + contract.nextPaymentAt() + ':' + now);
        TransferRequest request = new TransferRequest(transferId, settlement, TransferIntent.PAYMENT,
                legs, revisions, "employment_contract:" + contract.npcId(), now);
        TransferReceipt receipt = new TransferService(rights, ledger).submit(request, endpoints, now);
        if (receipt.successful()) {
            return new PaymentAttempt(PaymentResult.PAID, amount, "工资已通过统一转移账本进入私人储物");
        }
        return switch (receipt.status()) {
            case CAPACITY_EXCEEDED -> PaymentAttempt.blocked(PaymentResult.RECEIVER_UNAVAILABLE,
                    receipt.reason());
            case INSUFFICIENT_ITEMS -> PaymentAttempt.blocked(PaymentResult.INSUFFICIENT_FUNDS,
                    receipt.reason());
            case ENDPOINT_MISSING -> PaymentAttempt.blocked(PaymentResult.TREASURY_UNLOADED,
                    receipt.reason());
            default -> PaymentAttempt.blocked(PaymentResult.RECEIVER_UNAVAILABLE,
                    "工资转移未提交：" + receipt.reason());
        };
    }

    private static int countEmeralds(Container container) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(Items.EMERALD)) count += stack.getCount();
        }
        return count;
    }

    private static int emeraldCapacity(Container container) {
        int capacity = 0;
        ItemStack sample = new ItemStack(Items.EMERALD);
        int max = Math.min(container.getMaxStackSize(), sample.getMaxStackSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) capacity += max;
            else if (stack.is(Items.EMERALD)) capacity += Math.max(0, max - stack.getCount());
        }
        return capacity;
    }

    private static void adjustMorale(MinecraftServer server, UUID npcId, int amount) {
        NpcBase npc = CharacterRegistryData.get(server).findLoadedNpc(server, npcId);
        if (npc != null) npc.adjustMoral(amount);
    }

    private static String npcName(MinecraftServer server, UUID npcId) {
        CharacterRecord record = CharacterRegistryData.get(server).byId(npcId);
        return record == null ? npcId.toString().substring(0, 8) : record.name();
    }

    private static void notifyEmployer(MinecraftServer server, NpcEmploymentContract contract,
                                       String message, boolean important) {
        CharacterRegistryData characters = CharacterRegistryData.get(server);
        for (var player : server.getPlayerList().getPlayers()) {
            CharacterRecord current = characters.current(player.getUUID());
            if (current != null && current.id().equals(contract.employerCharacterId())) {
                player.sendSystemMessage(Component.literal(message), important);
            }
        }
    }

    @Nullable
    private static ServerLevel levelForLife(MinecraftServer server, NpcLifeRecord life) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(life.dimension())) return level;
        }
        return null;
    }

    private enum PaymentResult { PAID, RECEIVER_UNAVAILABLE, TREASURY_UNLOADED, INSUFFICIENT_FUNDS }
    private record TreasurySource(WarehouseRecord record, Container container, int emeralds) {}
    private record PaymentAttempt(PaymentResult result, int amount, String reason) {
        private static PaymentAttempt blocked(PaymentResult result, String reason) {
            return new PaymentAttempt(result, 0, reason);
        }
    }
}
