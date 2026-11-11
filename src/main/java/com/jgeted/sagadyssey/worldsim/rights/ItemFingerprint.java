package com.jgeted.sagadyssey.worldsim.rights;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.RegistryOps;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import java.util.TreeMap;

/** Item type plus canonical persistent component identity; stack count is excluded. */
public record ItemFingerprint(String itemId, int componentsHash) {
    public ItemFingerprint {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("blank item id");
    }

    public static ItemFingerprint of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("empty stack");
        return new ItemFingerprint(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                persistentComponentsHash(stack));
    }

    public boolean matches(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                && componentsHash == persistentComponentsHash(stack);
    }

    // Component types use identity hash codes: hashing their map is not stable
    // across JVMs. Hash the persisted codec representation in canonical key order.
    private static int persistentComponentsHash(ItemStack stack) {
        var server = ServerLifecycleHooks.getCurrentServer();
        com.mojang.serialization.DynamicOps<JsonElement> ops = server == null
                ? JsonOps.INSTANCE : RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        JsonElement encoded = DataComponentPatch.CODEC.encodeStart(ops, stack.getComponentsPatch()).getOrThrow();
        String canonical = canonicalJson(encoded);
        return canonical.equals("{}") ? 0 : ("persistent-components-v1:" + canonical).hashCode();
    }

    private static String canonicalJson(JsonElement value) {
        if (value.isJsonObject()) {
            var sorted = new TreeMap<String, JsonElement>();
            value.getAsJsonObject().entrySet().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            var result = new StringBuilder("{");
            sorted.forEach((key, child) -> {
                if (result.length() > 1) result.append(',');
                result.append(new com.google.gson.JsonPrimitive(key)).append(':').append(canonicalJson(child));
            });
            return result.append('}').toString();
        }
        if (value.isJsonArray()) {
            var result = new StringBuilder("[");
            for (JsonElement child : value.getAsJsonArray()) {
                if (result.length() > 1) result.append(',');
                result.append(canonicalJson(child));
            }
            return result.append(']').toString();
        }
        return value.toString();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Item", itemId);
        tag.putInt("Components", componentsHash);
        return tag;
    }

    public static ItemFingerprint load(CompoundTag tag) {
        return new ItemFingerprint(tag.getStringOr("Item", ""), tag.getIntOr("Components", 0));
    }
}
