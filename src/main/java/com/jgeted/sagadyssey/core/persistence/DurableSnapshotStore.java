package com.jgeted.sagadyssey.core.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;

/** 单线程拥有的有界意图存储；后台只接收不可变值，确切内容已原子发布后才确认。 */
public final class DurableSnapshotStore<K, V> implements AutoCloseable {
    public interface Codec<V> {
        int maxBytes();
        int capacity();
        byte[] write(Collection<V> records) throws IOException;
        Collection<V> read(byte[] bytes) throws IOException;
    }
    @FunctionalInterface public interface Publisher { void publish(Path temporary, Path destination) throws IOException; }
    public enum Status { APPLIED, PENDING, UNKNOWN, UNAVAILABLE }
    public record Reply<V>(Status status, Optional<V> durable, String reason) {}
    private final Thread owner = Thread.currentThread();
    private final Path file;
    private final Codec<V> codec;
    private final Function<V, K> key;
    private final BiPredicate<V, V> follows;
    private final Publisher publisher;
    private final ThreadPoolExecutor writer;
    private final Map<K, V> desired = new LinkedHashMap<>();
    private Map<K, V> durable, writing;
    private CompletableFuture<Void> pending;
    private String failure = "";
    private long epoch;
    private boolean closed;
    public DurableSnapshotStore(Path file, Codec<V> codec, Function<V, K> key, BiPredicate<V, V> follows, String worker) throws IOException {
        this(file, codec, key, follows, worker, (temporary, destination) -> Files.move(temporary, destination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }
    public DurableSnapshotStore(Path file, Codec<V> codec, Function<V, K> key, BiPredicate<V, V> follows, String worker, Publisher publisher) throws IOException {
        this.file = file.toAbsolutePath().normalize(); this.codec = Objects.requireNonNull(codec); this.key = Objects.requireNonNull(key);
        this.follows = Objects.requireNonNull(follows); this.publisher = Objects.requireNonNull(publisher);
        if (present(this.file)) {
            if (!Files.isRegularFile(this.file, LinkOption.NOFOLLOW_LINKS) || Files.size(this.file) > codec.maxBytes()) throw new IOException("invalid durable snapshot file");
            try (var input = Files.newInputStream(this.file)) {
                var bytes = input.readNBytes(codec.maxBytes()); if (input.read() != -1) throw new IOException("snapshot exceeds byte limit");
                for (V value : codec.read(bytes)) if (desired.size() >= codec.capacity() || desired.putIfAbsent(key.apply(value), value) != null) {
                    throw new IOException("duplicate or excessive durable records");
                }
            }
        } else if (present(this.file.resolveSibling(this.file.getFileName() + ".old")) || present(this.file.resolveSibling(this.file.getFileName() + "_old"))) {
            throw new IOException("missing primary snapshot with existing backup; review required");
        }
        durable = Map.copyOf(desired);
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            var thread = new Thread(task, worker); thread.setDaemon(true); return thread;
        });
    }
    public int capacity() { return codec.capacity(); }
    public long epoch() { checkThread(); poll(); return epoch; }
    public Optional<V> desired(K id) { checkThread(); return Optional.ofNullable(desired.get(id)); }
    public Optional<V> durable(K id) { checkThread(); poll(); return Optional.ofNullable(durable.get(id)); }
    public Collection<V> desiredValues() { checkThread(); return List.copyOf(desired.values()); }
    public Collection<V> durableValues() { checkThread(); poll(); return List.copyOf(durable.values()); }
    public Collection<V> recover() {
        checkThread(); poll();
        if (pending != null || !failure.isEmpty()) throw new IllegalStateException("recovery requires a confirmed checkpoint");
        return List.copyOf(durable.values());
    }
    public Reply<V> persist(V value) {
        checkThread(); Objects.requireNonNull(value);
        if (closed) return reply(Status.UNAVAILABLE, null, "snapshot store closed");
        poll(); K id = key.apply(value); V previous = desired.get(id);
        if (previous != null && !follows.test(previous, value)) return reply(Status.UNKNOWN, null, "snapshot request/revision conflict");
        if (previous == null && desired.size() >= codec.capacity()) return reply(Status.UNAVAILABLE, null, "snapshot capacity reached; no records evicted");
        desired.put(id, value);
        if (value.equals(durable.get(id))) return reply(Status.APPLIED, durable.get(id), "");
        if (!failure.isEmpty()) return reply(Status.UNKNOWN, null, failure);
        if (pending == null) {
            writing = Map.copyOf(desired); var snapshot = writing;
            pending = CompletableFuture.runAsync(() -> {
                try { write(snapshot.values()); } catch (IOException exception) { throw new CompletionException(exception); }
            }, writer);
        }
        return reply(Status.PENDING, null, "intent checkpoint is not yet durable");
    }
    private void poll() {
        if (pending == null || !pending.isDone()) return;
        try { pending.join(); durable = writing; epoch++; }
        catch (RuntimeException exception) {
            var cause = exception instanceof CompletionException && exception.getCause() != null ? exception.getCause() : exception;
            failure = "SNAPSHOT_WRITE_FAILED:" + cause.getClass().getSimpleName();
            System.getLogger(DurableSnapshotStore.class.getName()).log(System.Logger.Level.ERROR,
                    "Could not publish snapshot " + file.getFileName(), cause);
        }
        finally { pending = null; writing = null; }
    }
    public void retry() { checkThread(); if (closed) throw new IllegalStateException("snapshot store closed"); poll(); failure = ""; }
    private void write(Collection<V> records) throws IOException {
        byte[] bytes = codec.write(records); if (bytes.length > codec.maxBytes()) throw new IOException("encoded snapshot exceeds limit");
        Files.createDirectories(file.getParent()); Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            publish(temporary);
            try (var input = Files.newInputStream(file)) {
                if (!Arrays.equals(bytes, input.readNBytes(bytes.length)) || input.read() != -1) throw new IOException("published snapshot differs from intent");
            }
        } finally { Files.deleteIfExists(temporary); }
    }
    private void publish(Path temporary) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                publisher.publish(temporary, file);
                return;
            } catch (AccessDeniedException busy) {
                // A short Windows file handle can deny an atomic replacement. Retry the same flushed file on the writer.
                if (attempt >= 4 || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) throw busy;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20L * (attempt + 1)));
                if (Thread.currentThread().isInterrupted()) throw busy;
            }
        }
    }
    private static boolean present(Path file) throws IOException {
        try { Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException absent) { return false; }
    }
    private Reply<V> reply(Status status, V value, String reason) { return new Reply<>(status, Optional.ofNullable(value), reason); }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("snapshot owner thread required"); }
    /** 仅在停止/显式释放时调用。排空最后一份期望快照，避免旧写入结束后遗漏最终状态。 */
    @Override public void close() {
        checkThread(); if (closed) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (failure.isEmpty() && System.nanoTime() < deadline) {
            poll(); if (pending == null && desired.equals(durable)) break;
            if (pending == null) {
                var next = desired.entrySet().stream().filter(entry -> !entry.getValue().equals(durable.get(entry.getKey()))).findFirst();
                if (next.isPresent()) persist(next.get().getValue());
            }
            LockSupport.parkNanos(1_000_000);
        }
        closed = true; writer.shutdown();
        try { writer.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
