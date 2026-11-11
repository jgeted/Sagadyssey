package com.jgeted.sagadyssey.social.industry.forestry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * One renewal definition, shared by planting and seed retention. Public vanilla sapling/log
 * tags identify the resource family; they grant neither land rights nor a successful growth.
 * Only single-sapling native trees are supported. Dark oak needs a four-sapling project;
 * propagules/azaleas and fungi require their own planting semantics and remain unsupported.
 */
public final class ForestrySeedPolicy {
    private ForestrySeedPolicy() { }

    public static boolean isRenewalSeed(ItemStack stack) {
        return sapling(stack).isPresent();
    }

    public static Optional<BlockState> sapling(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ItemTags.SAPLINGS)
                || !(stack.getItem() instanceof BlockItem item)
                || !(item.getBlock() instanceof SaplingBlock)
                || item.getBlock() == Blocks.DARK_OAK_SAPLING) return Optional.empty();
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        // A similarly named mod item must not inherit unverified vanilla growth semantics.
        if (!key.getNamespace().equals("minecraft") || logTag(key).isEmpty()) return Optional.empty();
        return Optional.of(item.getBlock().defaultBlockState());
    }

    public static boolean matchesCut(ItemStack seed, String priorBlockState) {
        if (!isRenewalSeed(seed)) return false;
        try {
            var saved = TagParser.parseCompoundFully(priorBlockState);
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(saved.getStringOr("Name", "")));
            var family = logTag(BuiltInRegistries.ITEM.getKey(seed.getItem())).orElseThrow();
            return block.defaultBlockState().is(TagKey.create(Registries.BLOCK, family));
        } catch (Exception malformedReceipt) {
            // A partial/corrupt receipt cannot authorize renewal of a different resource family.
            return false;
        }
    }

    static Optional<Identifier> logTag(Identifier seed) {
        String path = seed.getPath();
        if (!path.endsWith("_sapling") || path.equals("dark_oak_sapling")) return Optional.empty();
        return Optional.of(Identifier.fromNamespaceAndPath(seed.getNamespace(),
                path.substring(0, path.length() - "_sapling".length()) + "_logs"));
    }
}
