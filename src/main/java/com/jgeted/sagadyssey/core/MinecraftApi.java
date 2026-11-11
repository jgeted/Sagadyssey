package com.jgeted.sagadyssey.core;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/** Compatibility helpers for vanilla interfaces whose semantics changed in Minecraft 1.21.11. */
public final class MinecraftApi {
    public static final int ANY_NUMERIC_TAG = 99;

    private MinecraftApi() {}

    public static @Nullable MinecraftServer server(Entity entity) {
        return server(entity.level());
    }

    public static @Nullable MinecraftServer server(Level level) {
        return level instanceof ServerLevel serverLevel ? serverLevel.getServer() : null;
    }

    public static boolean hasPermission(CommandSourceStack source, int level) {
        return switch (level) {
            case 1 -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
            case 2 -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
            case 3 -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
            case 4 -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
            default -> level <= 0;
        };
    }

    public static boolean containsTag(CompoundTag parent, String key, int type) {
        Tag child = parent.get(key);
        if (type == ANY_NUMERIC_TAG) {
            return child instanceof NumericTag;
        }
        return child != null && child.getId() == type;
    }

    public static ListTag listOfType(CompoundTag parent, String key, int type) {
        ListTag list = parent.getListOrEmpty(key);
        return list.stream().allMatch(element -> element.getId() == type) ? list : new ListTag();
    }

    public static byte elementType(ListTag list) {
        if (list.isEmpty()) {
            return Tag.TAG_END;
        }
        byte firstType = list.get(0).getId();
        return list.stream().allMatch(element -> element.getId() == firstType) ? firstType : Tag.TAG_END;
    }

    public static final com.mojang.serialization.MapCodec<CompoundTag> COMPOUND_FIELDS =
            com.mojang.serialization.MapCodec.assumeMapUnsafe(CompoundTag.CODEC);

    public static CompoundTag saveEntity(Entity entity) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    public static void loadEntity(Entity entity, CompoundTag tag) {
        entity.load(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess(), tag));
    }

    public static CompoundTag saveItem(net.minecraft.world.item.ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        if (stack.isEmpty()) return new CompoundTag();
        var ops = registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        return (CompoundTag) net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
    }

    public static java.util.Optional<net.minecraft.world.item.ItemStack> parseItem(
            net.minecraft.core.HolderLookup.Provider registries, Tag tag) {
        if (tag == null) return java.util.Optional.empty();
        return net.minecraft.world.item.ItemStack.CODEC.parse(
                registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag).result();
    }

    public static net.minecraft.world.item.ItemStack parseItemOptional(
            net.minecraft.core.HolderLookup.Provider registries, Tag tag) {
        return parseItem(registries, tag).orElse(net.minecraft.world.item.ItemStack.EMPTY);
    }

    public static void saveItems(CompoundTag tag, net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> items,
            net.minecraft.core.HolderLookup.Provider registries) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, registries);
        net.minecraft.world.ContainerHelper.saveAllItems(output, items);
        tag.merge(output.buildResult());
    }

    public static void loadItems(CompoundTag tag, net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> items,
            net.minecraft.core.HolderLookup.Provider registries) {
        net.minecraft.world.ContainerHelper.loadAllItems(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, registries, tag), items);
    }

    public static ServerLevel serverLevel(Entity entity) {
        if (entity.level() instanceof ServerLevel serverLevel) return serverLevel;
        throw new IllegalStateException("Expected server entity " + entity.getUUID());
    }

    public static void kill(Entity entity) {
        entity.kill(serverLevel(entity));
    }

    public static java.util.@Nullable UUID ownerId(net.minecraft.world.entity.OwnableEntity entity) {
        var owner = entity.getOwnerReference();
        return owner == null ? null : owner.getUUID();
    }

    public static boolean isDigger(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
                || minesTag(stack, BlockTags.MINEABLE_WITH_PICKAXE, BlockTags.MINEABLE_WITH_AXE,
                        BlockTags.MINEABLE_WITH_SHOVEL, BlockTags.MINEABLE_WITH_HOE);
    }

    public static boolean isSword(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || minesTag(stack, BlockTags.SWORD_EFFICIENT);
    }

    @SafeVarargs
    private static boolean minesTag(ItemStack stack, TagKey<Block>... blockTags) {
        var tool = stack.get(DataComponents.TOOL);
        if (tool == null) return false;
        for (var rule : tool.rules()) {
            if (rule.speed().isEmpty()) continue;
            var blocks = rule.blocks().unwrapKey();
            for (var tag : blockTags) {
                if (blocks.filter(tag::equals).isPresent()) return true;
            }
        }
        return false;
    }

    public static boolean isArmor(net.minecraft.world.item.ItemStack stack) {
        var equipment = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        return equipment != null && equipment.slot().isArmor();
    }

    public static int particleColor(org.joml.Vector3f color) {
        return Math.round(color.x * 255) << 16 | Math.round(color.y * 255) << 8 | Math.round(color.z * 255);
    }


    public static final com.mojang.serialization.Codec<com.google.gson.JsonElement> JSON_ELEMENT =
            com.mojang.serialization.Codec.PASSTHROUGH.xmap(
                    dynamic -> dynamic.convert(com.mojang.serialization.JsonOps.INSTANCE).getValue(),
                    element -> new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JsonOps.INSTANCE, element));

    public static CompoundTag saveRules(ServerLevel level) {
        return (CompoundTag) net.minecraft.world.level.gamerules.GameRules.codec(level.enabledFeatures())
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, level.getGameRules()).getOrThrow();
    }

    public static void loadRules(ServerLevel level, CompoundTag tag) {
        var rules = net.minecraft.world.level.gamerules.GameRules.codec(level.enabledFeatures())
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).getOrThrow();
        level.getGameRules().setAll(rules, level.getServer());
    }

}
