package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.AssetClaim;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLeg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅回收本项目已经实际安放并有回执的床，不赋予拆取任意家具的权限。 */
final class SettlementBedRelocation {
    private SettlementBedRelocation() { }

    static GrowthLayout.Placement temporary(NaturalVillageBuilding village) {
        var bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).rotate(village.rotation());
        return new GrowthLayout.Placement(Items.WHITE_BED,
                List.of(NaturalVillageLayout.at(village, new BlockPos(6, 1, 12)),
                        NaturalVillageLayout.at(village, new BlockPos(6, 1, 13))),
                List.of(bed.setValue(BedBlock.PART, BedPart.FOOT), bed.setValue(BedBlock.PART, BedPart.HEAD)));
    }

    static boolean originalBedPresent(ServerLevel level, GrowthLayout.Placement bed) {
        for (int i = 0; i < bed.positions().size(); i++) {
            var position = bed.positions().get(i);
            if (!level.hasChunkAt(position)) return false;
            var state = level.getBlockState(position);
            var expected = bed.states().get(i);
            if (!state.is(expected.getBlock()) || state.getValue(BedBlock.PART) != expected.getValue(BedBlock.PART)
                    || state.getValue(BedBlock.FACING) != expected.getValue(BedBlock.FACING)) return false;
        }
        return true;
    }

    static String work(NpcBase npc, CompoundTag row, CompoundTag task, long now) {
        var village = SettlementCoordinatorData.building(row);
        var growth = SettlementGrowthData.get(MinecraftApi.server(npc));
        var original = temporary(village);
        var level = (ServerLevel) npc.level();
        var inventory = npc.getPersonalInventory();
        var personal = ContainerIds.npcPersonal(npc.getCharacterId());
        var owner = SubjectRef.institution(village.settlementId());
        var character = SubjectRef.character(npc.getCharacterId());
        var rights = RightsStateData.get(MinecraftApi.server(npc));
        if (!task.getBooleanOr("Cargo", false)) {
            if (!SettlementCoordinatorData.done(row, "TEMP_BED") || SettlementCoordinatorData.job(row, "HOUSE").getIntOr("Placed", 0) < 56) return "YIELD";
            if (!growth.near(npc, original.positions().getFirst())) return "前往原临时床，准备真实拆取";
            var sleeper = level.getEntity(NaturalVillageLifeData.body(village, 2));
            if (sleeper instanceof NpcBase resident && resident.isSleeping()) return "YIELD";
            if (!originalBedPresent(level, original)) return "原床已变化或不可读取，保留核对责任";
            int slot = -1;
            for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).isEmpty()) { slot = i; break; }
            if (slot < 0) return "YIELD";
            task.putInt("Work", task.getIntOr("Work", 0) + 1);
            if (task.getIntOr("Work", 0) < 40) return "原协作者正在拆取自建床";
            rights.ensureContext(personal, "npc:" + npc.getCharacterId() + ":personal", character, character, AccessPolicy.PRIVATE);
            var before = rights.snapshotFor(Set.of(personal));
            task.putBoolean("Submitting", true);
            try {
                for (var position : original.positions()) {
                    if (!level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                        throw new IllegalStateException("床位拆取失败");
                    }
                }
                inventory.setItem(slot, new ItemStack(Items.WHITE_BED));
                rights.putClaim(new AssetClaim(ContainerIds.named(task.getStringOr("Id", "") + ":recovered"), personal, slot,
                        SettlementCoordinatorData.item(Items.WHITE_BED), 1, owner, character, Set.of(), Set.of(),
                        "recovered-from:" + SettlementCoordinatorData.job(row, "TEMP_BED").getStringOr("Id", ""), 0));
                rights.bumpContext(personal);
                task.putBoolean("Cargo", true);
                task.putLong("RecoveredAt", now);
                task.putString("RecoveryReceipt", task.getStringOr("Id", "") + ":recovered");
                task.putLong("LastProgress", now);
                task.remove("Submitting");
                // 原分配在实际新床安放时交接；期间旧坐标没有床，生活入口不能虚构睡眠。
                return "原床已拆成一件实物，由原协作者携带；没有新增床";
            } catch (RuntimeException error) {
                inventory.setItem(slot, ItemStack.EMPTY);
                rights.restore(before);
                for (int i = 0; i < original.positions().size(); i++) level.setBlock(original.positions().get(i), original.states().get(i), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                task.remove("Submitting");
                task.putBoolean("Review", true);
                return "拆床事务回退待核对：" + error.getMessage();
            }
        }
        if (!growth.near(npc, NaturalVillageLayout.at(village, NaturalVillageLayout.PUBLIC))) return "携带原床前往施工公共仓";
        var target = NaturalVillageEconomyData.storeId(village, 3);
        if (!growth.transfer(npc, task, ContainerIds.named(task.getStringOr("Id", "") + ":return"), owner, TransferIntent.RETURN,
                List.of(new TransferLeg(personal, target, SettlementCoordinatorData.item(Items.WHITE_BED), 1)),
                Map.of(personal, inventory, target, SettlementCoordinatorData.store(MinecraftApi.server(npc), village, 3)))) return "YIELD";
        task.remove("Cargo");
        task.store("Delivery", UUIDUtil.CODEC, task.read("LastTransfer", UUIDUtil.CODEC).orElseThrow());
        SettlementCoordinatorData.complete(task, now);
        return "原床实物已入仓，由正式施工安放到新屋";
    }
}
