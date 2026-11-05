package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Canonical, component-sensitive revision fingerprints; never an asset backup. */
public final class LodFingerprints {
    private LodFingerprints() {}
    public static String of(Tag tag) { var s=new StringBuilder(); append(tag,s); return text(s.toString()); }
    public static String text(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void append(Tag tag,StringBuilder out) {
        out.append(tag.getId()).append(':');
        if(tag instanceof CompoundTag compound) {
            compound.getAllKeys().stream().sorted().forEach(k->{out.append(k.length()).append(':').append(k);append(compound.get(k),out);});
        } else if(tag instanceof ListTag list) {out.append(list.size()).append(':');list.forEach(t->append(t,out));}
        else {var value=tag.toString();out.append(value.length()).append(':').append(value);}
        out.append(';');
    }
    public static String inventory(NpcBase npc) {
        return of(inventoryTag(npc));
    }
    public static CompoundTag inventoryTag(NpcBase npc) {
        var tag=new CompoundTag(); var stacks=new ListTag();
        var bag=npc.getPersonalInventory();var cargo=npc.getTransportInventory();
        for(int i=0;i<bag.getContainerSize();i++)stacks.add(stack(npc,bag.getItem(i)));
        for(int i=0;i<cargo.getContainerSize();i++)stacks.add(stack(npc,cargo.getItem(i)));
        for(var slot:EquipmentSlot.values())stacks.add(stack(npc,npc.getItemBySlot(slot)));
        for(var value:npc.getInventoryMigrationOverflowSnapshot())stacks.add(stack(npc,value));
        tag.put("OrderedSlots",stacks);return tag;
    }
    private static Tag stack(NpcBase npc,ItemStack stack) {return stack.isEmpty()?new CompoundTag():stack.save(npc.level().registryAccess());}
}
