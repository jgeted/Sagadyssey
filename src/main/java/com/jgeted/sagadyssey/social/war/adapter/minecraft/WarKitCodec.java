package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarMobilizationCodec;
import com.jgeted.sagadyssey.social.war.application.WarMobilizationService;
import com.jgeted.sagadyssey.social.war.domain.WarMobilization;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.nbt.*;
import java.io.IOException;
import java.util.*;

public final class WarKitCodec implements com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore.Codec<WarKitRecord> {
    private final VersionCodec current=new VersionCodec("sagadyssey:war_kit/v3",false),legacy=new VersionCodec("sagadyssey:war_kit/v2",true);
    public int maxBytes(){return current.maxBytes();}
    public int capacity(){return current.capacity();}
    public byte[] write(Collection<WarKitRecord> values)throws IOException{return current.write(values);}
    public Collection<WarKitRecord> read(byte[] bytes)throws IOException{
        try{return current.read(bytes);}catch(IOException failure){try{return legacy.read(bytes);}catch(IOException old){failure.addSuppressed(old);throw failure;}}
    }
    private static final class VersionCodec extends NbtSnapshotCodec<WarKitRecord>{
    private final boolean legacy;
    private static final WarMobilizationCodec REQUEST = new WarMobilizationCodec(8192, 1);
    private static final PhysicalItemOperationCodec ITEM = new PhysicalItemOperationCodec();
    private VersionCodec(String kind,boolean legacy) { super(kind, 8 * 1024 * 1024, WarMobilizationService.RECORD_LIMIT);this.legacy=legacy; }
    protected UUID identity(WarKitRecord value) { return value.request().id(); }
    protected CompoundTag encode(WarKitRecord value) {
        var tag = new CompoundTag();
        try { tag.putByteArray("Request", REQUEST.write(List.of(WarMobilization.start(value.request())))); }
        catch (IOException exception) { throw new IllegalArgumentException(exception); }
        tag.store("Body", UUIDUtil.CODEC, value.body()); tag.put("Armory", value.armory().save()); var lines = new ListTag();
        for (var line : value.lines()) { var row = new CompoundTag(); row.put("Item", line.item().save()); row.putInt("Quantity", line.quantity()); lines.add(row); } tag.put("Lines", lines);
        tag.put("Issues", attempts(value.issues())); tag.put("Returns", attempts(value.returns())); return tag;
    }
    private static ListTag attempts(List<PhysicalItemOperation.Intent> values) { var rows = new ListTag(); for (var value : values) { var row = new CompoundTag(); row.putByteArray("Intent", intent(value)); rows.add(row); } return rows; }
    private static byte[] intent(PhysicalItemOperation.Intent value) {
        try { return ITEM.write(List.of(new PhysicalItemOperation(value, PhysicalItemOperation.Phase.INTENT, Optional.empty()))); }
        catch (IOException exception) { throw new IllegalArgumentException(exception); }
    }
    protected WarKitRecord decode(CompoundTag tag) {
        fields(tag, Set.of("Request", "Body", "Armory", "Lines", "Issues", "Returns"));
        try {
            var requests = REQUEST.read(bytes(tag, "Request")); if (requests.size() != 1 || requests.getFirst().revision() != 1 || requests.getFirst().stage() != WarMobilization.Stage.CLAIM) throw new IllegalArgumentException("invalid kit request");
            if (!(tag.get("Armory") instanceof CompoundTag armory) || !(tag.get("Lines") instanceof ListTag rows) || rows.size() > 6 || !rows.isEmpty() && MinecraftApi.elementType(rows) != Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid kit fields");
            var lines = new ArrayList<WarKitRecord.Line>(); for (Tag raw : rows) {
                var row = (CompoundTag) raw; fields(row, Set.of("Item", "Quantity")); if (!(row.get("Item") instanceof CompoundTag item) || !MinecraftApi.containsTag(row, "Quantity", Tag.TAG_INT)) throw new IllegalArgumentException("invalid kit line");
                fields(item, Set.of("Item", "Components")); string(item, "Item"); if (!MinecraftApi.containsTag(item, "Components", Tag.TAG_INT)) throw new IllegalArgumentException("invalid kit item");
                lines.add(new WarKitRecord.Line(ItemFingerprint.load(item), row.getIntOr("Quantity", 0)));
            }
            if(legacy&&(lines.isEmpty()||ItemLocation.load(armory).kind()!=ItemLocation.Kind.BLOCK))throw new IllegalArgumentException("v2 requires a nonempty block-armory loan plan");
            return new WarKitRecord(requests.getFirst().request(), uuid(tag, "Body"), ItemLocation.load(armory), lines,
                    attempts(tag, "Issues"), attempts(tag, "Returns"));
        } catch (IOException exception) { throw new IllegalArgumentException("invalid kit envelope", exception); }
    }
    private static byte[] bytes(CompoundTag tag, String key) { if (!MinecraftApi.containsTag(tag, key, Tag.TAG_BYTE_ARRAY) || tag.getByteArray(key).orElse(new byte[0]).length > 32_768) throw new IllegalArgumentException("invalid kit byte array"); return tag.getByteArray(key).orElse(new byte[0]); }
    private static List<PhysicalItemOperation.Intent> attempts(CompoundTag tag, String key) throws IOException {
        if (!(tag.get(key) instanceof ListTag rows) || rows.size() > 32 || !rows.isEmpty() && MinecraftApi.elementType(rows) != Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid kit attempts");
        var result = new ArrayList<PhysicalItemOperation.Intent>(); for (Tag raw : rows) { var row = (CompoundTag) raw; fields(row, Set.of("Intent")); result.add(intent(bytes(row, "Intent"))); } return List.copyOf(result);
    }
    private static PhysicalItemOperation.Intent intent(byte[] bytes) throws IOException {
        var values = ITEM.read(bytes); if (values.size() != 1 || values.iterator().next().phase() != PhysicalItemOperation.Phase.INTENT) throw new IllegalArgumentException("expected kit intent"); return values.iterator().next().intent();
    }
    }
}
