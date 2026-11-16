package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 战争自身检查点的确定性编码，不读写 Minecraft SavedData 或共享领域。
 * 外层校验用于发现损坏，不构成权限证明或跨文件原子事务。
 */
public final class WarStateCodec {
    private static final int MAGIC = 0x53475752;
    private static final int SCHEMA = 2;
    private static final int DIGEST_BYTES = 32;

    public record Limits(int maxBytes, int maxUnits, int maxOperations, int maxPendingPerUnit) {
        public Limits {
            if (maxBytes < 64 || maxUnits < 1 || maxOperations < maxUnits || maxPendingPerUnit < 1) {
                throw new IllegalArgumentException("invalid codec limits");
            }
        }
        public static Limits defaults() { return new Limits(16 * 1024 * 1024, 1024, 65_536, 64); }
    }

    public sealed interface ReadResult permits Restored, Unsupported {}
    public record Restored(WarState state) implements ReadResult {
        public Restored { Objects.requireNonNull(state); }
    }
    public record Unsupported(byte[] originalBytes, String reason) implements ReadResult {
        public Unsupported {
            originalBytes = originalBytes.clone();
            Objects.requireNonNull(reason);
        }
        @Override public byte[] originalBytes() { return originalBytes.clone(); }
    }

    private final Limits limits;
    public WarStateCodec(Limits limits) { this.limits = Objects.requireNonNull(limits); }
    public int maxEncodedBytes() { return limits.maxBytes(); }

    public byte[] write(WarState state) throws IOException {
        Objects.requireNonNull(state);
        checkCount(state.units().size(), limits.maxUnits());
        checkCount(state.operations().size(), limits.maxOperations());
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new BoundedOutput(bytes, limits.maxBytes() - 44))) {
            out.writeLong(state.lastChangedTick());
            out.writeInt(state.units().size());
            for (UUID id : sorted(state.units().keySet())) writeUnit(out, state.units().get(id));
            out.writeInt(state.operations().size());
            for (UUID id : sorted(state.operations().keySet())) {
                WarState.Operation operation = state.operations().get(id);
                writeCommand(out, operation.command());
                WarResult.Receipt receipt = operation.receipt();
                out.writeLong(receipt.unitRevision());
                out.writeLong(receipt.orderSequence());
                out.writeLong(receipt.appliedAtTick());
            }
            out.writeLong(state.generation());
            out.writeBoolean(state.previousArchive().isPresent());
            if (state.previousArchive().isPresent()) {
                WarArchiveRef archive = state.previousArchive().get();
                out.writeLong(archive.generation()); text(out, archive.sha256());
                out.writeInt(archive.bytes()); out.writeLong(archive.checkpointTick());
            }
            out.writeInt(state.anchors().size());
            for (UUID id : sorted(state.anchors().keySet())) {
                uuid(out, id); WarState.Anchor anchor = state.anchors().get(id);
                out.writeLong(anchor.revision()); out.writeLong(anchor.lastIssuedSequence());
            }
        }
        byte[] payload = bytes.toByteArray();
        var encoded = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(encoded)) {
            out.writeInt(MAGIC);
            out.writeInt(SCHEMA);
            out.writeInt(payload.length);
            out.write(payload);
            out.write(digest(payload));
        }
        return encoded.toByteArray();
    }

    public ReadResult read(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded);
        if (encoded.length > limits.maxBytes()) throw new IOException("war checkpoint exceeds byte limit");
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != MAGIC) throw new IOException("invalid war checkpoint magic");
            int version = in.readInt();
            int length = count(in, limits.maxBytes() - 44);
            if (version < 1 || length != encoded.length - 44) throw new IOException("invalid checkpoint envelope");
            byte[] payload = in.readNBytes(length);
            byte[] expected = in.readNBytes(DIGEST_BYTES);
            if (expected.length != DIGEST_BYTES || !MessageDigest.isEqual(expected, digest(payload))) {
                throw new IOException("war checkpoint checksum mismatch");
            }
            if (version > SCHEMA) return new Unsupported(encoded, "UNSUPPORTED_SCHEMA");
            try {
                return new Restored(readPayload(payload, version));
            } catch (UnsupportedEntry exception) {
                return new Unsupported(encoded, "UNSUPPORTED_OPERATION");
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IOException("inconsistent war checkpoint", exception);
            }
        }
    }

    private WarState readPayload(byte[] payload, int schema) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            long changedAt = in.readLong();
            int unitCount = count(in, limits.maxUnits());
            Map<UUID, WarUnit> units = new HashMap<>();
            for (int i = 0; i < unitCount; i++) {
                WarUnit unit = readUnit(in, schema);
                if (units.putIfAbsent(unit.id(), unit) != null) throw new IOException("duplicate unit");
            }
            int operationCount = count(in, limits.maxOperations());
            Map<UUID, WarState.Operation> operations = new HashMap<>();
            for (int i = 0; i < operationCount; i++) {
                WarCommand command = readCommand(in, schema);
                var receipt = new WarResult.Receipt(command.operationId(), command.generation(), command.unitId(),
                        in.readLong(), in.readLong(), in.readLong());
                if (operations.putIfAbsent(command.operationId(), new WarState.Operation(command, receipt)) != null) {
                    throw new IOException("duplicate operation");
                }
            }
            long generation = 0;
            Optional<WarArchiveRef> archive = Optional.empty();
            Map<UUID, WarState.Anchor> anchors = new HashMap<>();
            if (schema >= 2) {
                generation = in.readLong();
                if (in.readBoolean()) archive = Optional.of(new WarArchiveRef(in.readLong(), text(in), in.readInt(), in.readLong()));
                int anchorCount = count(in, limits.maxUnits());
                for (int i = 0; i < anchorCount; i++) {
                    UUID id = uuid(in);
                    if (anchors.putIfAbsent(id, new WarState.Anchor(in.readLong(), in.readLong())) != null) {
                        throw new IOException("duplicate archive unit anchor");
                    }
                }
            }
            if (in.available() != 0) throw new IOException("unexpected checkpoint payload tail");
            return new WarState(changedAt, units, operations, generation, archive, anchors);
        }
    }

    private void writeUnit(DataOutputStream out, WarUnit unit) throws IOException {
        uuid(out, unit.id()); uuid(out, unit.armyId()); key(out, unit.troopType());
        out.writeInt(unit.definitionVersion()); out.writeLong(unit.revision()); out.writeLong(unit.lastIssuedSequence());
        out.writeBoolean(unit.currentOrder().isPresent());
        if (unit.currentOrder().isPresent()) writeOrder(out, unit.currentOrder().get());
        checkCount(unit.pendingOrders().size(), limits.maxPendingPerUnit());
        out.writeInt(unit.pendingOrders().size());
        for (WarUnit.Order order : unit.pendingOrders()) writeOrder(out, order);
    }

    private WarUnit readUnit(DataInputStream in, int schema) throws IOException {
        UUID id = uuid(in), army = uuid(in);
        WarKey troop = key(in);
        int version = in.readInt();
        long revision = in.readLong(), sequence = in.readLong();
        Optional<WarUnit.Order> current = in.readBoolean() ? Optional.of(readOrder(in, schema)) : Optional.empty();
        int size = count(in, limits.maxPendingPerUnit());
        List<WarUnit.Order> pending = new ArrayList<>();
        for (int i = 0; i < size; i++) pending.add(readOrder(in, schema));
        return new WarUnit(id, army, troop, version, revision, sequence, current, pending);
    }

    private static void writeOrder(DataOutputStream out, WarUnit.Order order) throws IOException {
        uuid(out, order.id()); out.writeLong(order.generation()); uuid(out, order.issuerId()); out.writeLong(order.sequence());
        intent(out, order.intent()); out.writeLong(order.issuedAtTick());
        out.writeLong(order.deliveredAtTick()); out.writeLong(order.deliveryRevision());
    }

    private static WarUnit.Order readOrder(DataInputStream in, int schema) throws IOException {
        UUID id = uuid(in); long generation = schema >= 2 ? in.readLong() : 0;
        return new WarUnit.Order(id, generation, uuid(in), in.readLong(), intent(in),
                in.readLong(), in.readLong(), in.readLong());
    }

    private static void writeCommand(DataOutputStream out, WarCommand command) throws IOException {
        text(out, command instanceof WarCommand.CreateUnit ? "create_unit" : "issue_order");
        uuid(out, command.operationId()); out.writeLong(command.generation());
        uuid(out, command.actorId()); uuid(out, command.armyId()); uuid(out, command.unitId());
        if (command instanceof WarCommand.CreateUnit create) {
            key(out, create.troopType()); out.writeInt(create.definitionVersion());
        } else {
            WarCommand.IssueOrder issue = (WarCommand.IssueOrder) command;
            out.writeLong(issue.expectedRevision()); intent(out, issue.intent());
        }
    }

    private static WarCommand readCommand(DataInputStream in, int schema) throws IOException {
        String type = text(in);
        UUID operation = uuid(in); long generation = schema >= 2 ? in.readLong() : 0;
        UUID actor = uuid(in), army = uuid(in), unit = uuid(in);
        return switch (type) {
            case "create_unit" -> new WarCommand.CreateUnit(operation, generation, actor, army, unit, key(in), in.readInt());
            case "issue_order" -> new WarCommand.IssueOrder(operation, generation, actor, army, unit, in.readLong(), intent(in));
            default -> throw new UnsupportedEntry();
        };
    }

    private static void intent(DataOutputStream out, WarIntent intent) throws IOException {
        key(out, intent.kind()); out.writeInt(intent.definitionVersion());
        key(out, intent.target().dimension()); out.writeInt(intent.target().x());
        out.writeInt(intent.target().y()); out.writeInt(intent.target().z());
    }
    private static WarIntent intent(DataInputStream in) throws IOException {
        WarKey kind = key(in); int version = in.readInt();
        return new WarIntent(kind, version, new WarIntent.Position(key(in), in.readInt(), in.readInt(), in.readInt()));
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void key(DataOutputStream out, WarKey value) throws IOException { text(out, value.toString()); }
    private static WarKey key(DataInputStream in) throws IOException { return WarKey.parse(text(in)); }
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        checkCount(bytes.length, 256); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int size = count(in, 256);
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static int count(DataInputStream in, int max) throws IOException {
        int size = in.readInt(); checkCount(size, max); return size;
    }
    private static void checkCount(int size, int max) throws IOException {
        if (size < 0 || size > max) throw new IOException("war checkpoint count exceeds limit");
    }
    private static List<UUID> sorted(Set<UUID> ids) { return ids.stream().sorted().toList(); }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static final class UnsupportedEntry extends IOException {}

    private static final class BoundedOutput extends FilterOutputStream {
        private final int limit;
        private int written;
        private BoundedOutput(OutputStream out, int limit) { super(out); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (written >= limit) throw new IOException("war checkpoint byte limit exceeded");
            out.write(value); written++;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > limit - written) throw new IOException("war checkpoint byte limit exceeded");
            out.write(bytes, offset, length); written += length;
        }
    }
}
