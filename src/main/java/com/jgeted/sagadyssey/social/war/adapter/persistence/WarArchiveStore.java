package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.social.war.domain.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/** 不可变、内容寻址的冷归档。后台只接触纯快照和专属目录，不持有服务器或实体。 */
public final class WarArchiveStore implements AutoCloseable {
    @FunctionalInterface
    public interface Publisher { void publish(Path temporary, Path destination) throws IOException; }

    private final Path directory;
    private final WarStateCodec codec;
    private final Publisher publisher;
    private final ThreadPoolExecutor writer;
    private volatile boolean closed;

    public WarArchiveStore(Path directory, WarStateCodec codec) {
        this(directory, codec, (temporary, destination) -> Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE));
    }

    /** 显式发布接点用于检验落盘中断；不允许以非原子替换伪装成功。 */
    public WarArchiveStore(Path directory, WarStateCodec codec, Publisher publisher) {
        this.directory = directory.toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec);
        this.publisher = Objects.requireNonNull(publisher);
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "sagadyssey-war-archive"); thread.setDaemon(true); return thread;
        });
    }

    public CompletableFuture<WarArchiveRef> write(WarState state) {
        Objects.requireNonNull(state);
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return writeNow(state); }
                catch (IOException exception) { throw new CompletionException(exception); }
            }, writer);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public WarArchiveRef writeNow(WarState state) throws IOException {
        if (closed) throw new IOException("archive writer is closed");
        byte[] bytes = codec.write(state);
        var reference = new WarArchiveRef(state.generation(), hash(bytes), bytes.length, state.lastChangedTick());
        Files.createDirectories(directory);
        Path destination = path(reference);
        if (present(destination)) {
            read(reference); return reference;
        }
        Path temporary = Files.createTempFile(directory, "pending-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            publisher.publish(temporary, destination);
            // Confirm the published file before a compact checkpoint can reference it.
            read(reference);
            return reference;
        } finally {
            // Only this call's exact temporary file is eligible for removal.
            Files.deleteIfExists(temporary);
        }
    }

    public WarState read(WarArchiveRef reference) throws IOException {
        Path path = path(reference);
        if (reference.bytes() > codec.maxEncodedBytes() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) != reference.bytes()) throw new IOException("missing or oversized war archive");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(reference.bytes());
            if (bytes.length != reference.bytes() || input.read() != -1) throw new IOException("war archive length changed during read");
        }
        if (!hash(bytes).equals(reference.sha256())) throw new IOException("war archive checksum mismatch");
        if (!(codec.read(bytes) instanceof WarStateCodec.Restored restored)) throw new IOException("unsupported war archive");
        WarState state = restored.state();
        if (state.generation() != reference.generation() || state.lastChangedTick() != reference.checkpointTick()) {
            throw new IOException("war archive metadata mismatch");
        }
        return state;
    }

    /** 启动只验证最近一份归档及活动锚点；历史链按需读取，不全量扫盘或加载历史。 */
    public void verify(WarState active) throws IOException {
        if (active.previousArchive().isEmpty()) return;
        WarState previous = read(active.previousArchive().get());
        Map<UUID, WarState.Anchor> expected = new HashMap<>();
        for (WarUnit unit : previous.units().values()) {
            expected.put(unit.id(), new WarState.Anchor(unit.revision(), unit.lastIssuedSequence()));
            WarUnit current = active.units().get(unit.id());
            if (current == null || !current.armyId().equals(unit.armyId()) || !current.troopType().equals(unit.troopType())
                    || current.definitionVersion() != unit.definitionVersion()) throw new IOException("archive unit identity mismatch");
            long before = unit.currentOrder().map(WarUnit.Order::sequence).orElse(0L);
            long after = current.currentOrder().map(WarUnit.Order::sequence).orElse(0L);
            if (after < before || after == before && !current.currentOrder().equals(unit.currentOrder())) {
                throw new IOException("current order regressed or changed before its archive anchor");
            }
            if (after > before) {
                WarUnit.Order delivery = current.currentOrder().orElseThrow();
                if (delivery.deliveryRevision() <= unit.revision()
                        || delivery.deliveredAtTick() < active.previousArchive().orElseThrow().checkpointTick()) {
                    throw new IOException("delivery predates its archive anchor");
                }
            }
        }
        if (!active.anchors().equals(expected)) throw new IOException("archive anchors do not match published history");
        for (var entry : active.operations().entrySet()) {
            if (entry.getValue().command().generation() < active.generation()
                    && !entry.getValue().equals(previous.operations().get(entry.getKey()))) {
                throw new IOException("pinned acceptance does not match archived evidence");
            }
        }
    }

    public Path path(WarArchiveRef reference) { return directory.resolve(reference.sha256() + ".sgw"); }

    private static boolean present(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException absent) { return false; }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    @Override public void close() { closed = true; writer.shutdown(); }
}
