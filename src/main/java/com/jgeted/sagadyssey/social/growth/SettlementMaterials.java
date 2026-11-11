package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import java.util.*;

/** Functional material groups. Tags classify; existing title and transfer services authorize. */
public final class SettlementMaterials {
    public static final TagKey<Block> COVER = TagKey.create(Registries.BLOCK, Identifier.parse("sagadyssey:resources/cover_soils"));
    public static final TagKey<Block> QUARRY = TagKey.create(Registries.BLOCK, Identifier.parse("sagadyssey:resources/quarry_stones"));
    public static final TagKey<Block> WALL_BLOCKS = TagKey.create(Registries.BLOCK, Identifier.parse("sagadyssey:resources/masonry"));
    public static final TagKey<Item> WALL_ITEMS = TagKey.create(Registries.ITEM, Identifier.parse("sagadyssey:resources/masonry"));
    private SettlementMaterials() {}
    public static boolean quarry(BlockState state) { return state.is(QUARRY) && !(state.getBlock() instanceof net.minecraft.world.level.block.InfestedBlock) && !state.hasBlockEntity() && state.getFluidState().isEmpty(); }
    public static boolean mineable(BlockState state) {
        return quarry(state) || state.is(Tags.Blocks.COBBLESTONES_NORMAL)
                || state.is(Tags.Blocks.COBBLESTONES_DEEPSLATE) || state.is(Tags.Blocks.ORES);
    }
    public static boolean masonry(ItemStack stack) {
        return !stack.isEmpty() && stack.is(WALL_ITEMS) && stack.getComponentsPatch().isEmpty()
                && stack.getItem() instanceof BlockItem b && b.getBlock().defaultBlockState().is(WALL_BLOCKS) && !(b.getBlock() instanceof net.minecraft.world.level.block.InfestedBlock) && !b.getBlock().defaultBlockState().hasBlockEntity();
    }
    public static boolean masonry(BlockState state) { return state.is(WALL_BLOCKS) && !(state.getBlock() instanceof net.minecraft.world.level.block.InfestedBlock) && !state.hasBlockEntity(); }
    public static int available(MinecraftServer s, NaturalVillageBuilding b, int index) {
        var box = SettlementCoordinatorData.store(s,b,index); if(box==null)return 0;
        var items=new HashSet<Item>();
        for(int i=0;i<box.getContainerSize();i++)if(masonry(box.getItem(i)))items.add(box.getItem(i).getItem());
        return items.stream().mapToInt(item->SettlementCoordinatorData.available(s,b,index,item)).sum();
    }
    public static Item choose(MinecraftServer s,NaturalVillageBuilding b,int... indices) {
        var candidates=new TreeMap<String,Item>();
        for(int index:indices){var box=SettlementCoordinatorData.store(s,b,index);if(box==null)continue;
            for(int i=0;i<box.getContainerSize();i++){var stack=box.getItem(i);
                if(masonry(stack)&&SettlementCoordinatorData.available(s,b,index,stack.getItem())>0)
                    candidates.put(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getItem());}}
        // Preserve the original concrete blueprint material when it is available.
        return candidates.values().stream().filter(i->i==Items.COBBLESTONE).findFirst()
                .orElse(candidates.isEmpty()?Items.AIR:candidates.firstEntry().getValue());
    }
    /** Recover concrete cargo from the original successful receipt, never from current stock. */
    public static Item carried(MinecraftServer s,CompoundTag task,String receiptField,Item legacy,UUID expectedDestination) {
        if(!task.read(receiptField, UUIDUtil.CODEC).isPresent())return Items.AIR;
        var ledger=TransferLedgerData.get(s);var id=task.read(receiptField, UUIDUtil.CODEC).orElseThrow();
        var receipt=ledger.receipt(id);var identity=ledger.requestIdentity(id);
        if(receipt==null||!receipt.successful()||identity==null)return Items.AIR;
        var legs=MinecraftApi.listOfType(identity, "Legs",Tag.TAG_COMPOUND);
        if(legs.size()!=1||!legs.getCompoundOrEmpty(0).read("Destination", UUIDUtil.CODEC).isPresent()||!legs.getCompoundOrEmpty(0).read("Destination", UUIDUtil.CODEC).orElseThrow().equals(expectedDestination))return Items.AIR;
        var fp=ItemFingerprint.load(legs.getCompoundOrEmpty(0).getCompoundOrEmpty("Item"));
        if(fp.componentsHash()!=0)return Items.AIR;
        var item=BuiltInRegistries.ITEM.getValue(Identifier.parse(fp.itemId()));
        return masonry(new ItemStack(item))||item==legacy?item:Items.AIR;
    }
    public static Item declaredCargo(CompoundTag task) {
        var id=Identifier.tryParse(task.getStringOr("CarriedItem", ""));
        if(id==null)return Items.AIR;
        var item=BuiltInRegistries.ITEM.getValue(id);
        return masonry(new ItemStack(item))?item:Items.AIR;
    }
    public static GrowthLayout.Placement substitute(GrowthLayout.Placement original,Item actual) {
        if(original.material()!=Items.COBBLESTONE)return original;
        if(!masonry(new ItemStack(actual)))throw new IllegalArgumentException("unsupported masonry item");
        var block=((BlockItem)actual).getBlock();
        return new GrowthLayout.Placement(actual,original.positions(),
                original.states().stream().map(ignored->block.defaultBlockState()).toList());
    }
    public static boolean matches(GrowthLayout.Placement expected,BlockState actual,int index) {
        return expected.material()==Items.COBBLESTONE?masonry(actual):actual.is(expected.states().get(index).getBlock());
    }
}
