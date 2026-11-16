package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.domain.WarTransmission;
import net.minecraft.nbt.CompoundTag;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Immutable compressed dispatch pages. A hot checkpoint may reference a page only after read-back verification. */
final class WarDispatchArchive implements AutoCloseable {
    static final int PAGE_LIMIT = 96;
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final UUID PAGE_ID = new UUID(0, 0x57415244495350L);
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");

    record Ref(long sequence, long total, int count, int bytes, String sha) {
        Ref {
            if (sequence < 1 || count < 1 || count > PAGE_LIMIT || total < count || bytes < 20 || bytes > LIMIT
                    || sha == null || !DIGEST.matcher(sha).matches()) throw new IllegalArgumentException("invalid dispatch archive reference");
        }
    }

    record Page(Optional<Ref> previous, List<WarTransmission> records) {
        Page {
            Objects.requireNonNull(previous);
            records = List.copyOf(records);
            if (records.isEmpty() || records.size() > PAGE_LIMIT || records.stream().anyMatch(r -> r.received().isEmpty()))
                throw new IllegalArgumentException("archive pages contain confirmed receptions only");
        }

        long sequence() { return Math.addExact(previous.map(Ref::sequence).orElse(0L), 1); }
        long total() { return Math.addExact(previous.map(Ref::total).orElse(0L), records.size()); }
    }

    private final Path dir;
    private final PageCodec codec = new PageCodec();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
        var worker = new Thread(task, "sagadyssey-war-dispatch-archive");
        worker.setDaemon(true);
        return worker;
    });

    WarDispatchArchive(Path directory) { dir = directory; }

    CompletableFuture<Ref> write(Page page) {
        return CompletableFuture.supplyAsync(() -> {
            try { return publish(page); }
            catch (IOException e) { throw new CompletionException(e); }
        },writer);
    }

    private Ref publish(Page page) throws IOException {
        byte[] data = codec.write(List.of(page));
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(data); }
        byte[] compressed = bytes.toByteArray();
        var ref = new Ref(page.sequence(),page.total(),page.records().size(),compressed.length,digest(compressed));
        Files.createDirectories(dir);
        var target = path(ref);
        if (!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            var temporary = Files.createTempFile(dir,"dispatch-",".tmp");
            try {
                try (var channel = FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(compressed);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        read(ref);
        return ref;
    }

    void verify(Ref ref) throws IOException { read(ref); }

    Page read(Ref ref) throws IOException {
        Path path = path(ref);
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path) != ref.bytes())
            throw new IOException("missing or damaged dispatch archive page");
        byte[] compressed;
        try (var input = Files.newInputStream(path)) {
            compressed = input.readNBytes(ref.bytes());
            if (compressed.length != ref.bytes() || input.read() != -1 || !digest(compressed).equals(ref.sha()))
                throw new IOException("dispatch archive digest mismatch");
        }
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            var decoded = input.readNBytes(LIMIT);
            if (input.read() != -1) throw new IOException("dispatch archive expands beyond its limit");
            var rows = codec.read(decoded);
            if (rows.size() != 1) throw new IOException("invalid dispatch archive page");
            var page = rows.iterator().next();
            if (page.sequence() != ref.sequence() || page.total() != ref.total() || page.records().size() != ref.count())
                throw new IOException("dispatch archive metadata mismatch");
            return page;
        }
    }

    private Path path(Ref ref) { return dir.resolve(ref.sha()+".sgw.gz"); }

    static CompoundTag encodeRef(Ref ref) {
        var tag = new CompoundTag();
        tag.putLong("Sequence",ref.sequence());
        tag.putLong("Total",ref.total());
        tag.putInt("Count",ref.count());
        tag.putInt("Bytes",ref.bytes());
        tag.putString("Digest",ref.sha());
        return tag;
    }

    static Ref decodeRef(CompoundTag tag) {
        WarHazardCodec.fields(tag,"Sequence,Total,Count,Bytes,Digest");
        return new Ref(tag.getLong("Sequence").orElseThrow(IllegalArgumentException::new),
                tag.getLong("Total").orElseThrow(IllegalArgumentException::new),
                WarHazardCodec.integer(tag,"Count"),WarHazardCodec.integer(tag,"Bytes"),tag.getStringOr("Digest",""));
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    @Override public void close() { writer.shutdown(); }

    private static final class PageCodec extends NbtSnapshotCodec<Page> {
        private final WarDispatchCodec rows = new WarDispatchCodec();
        PageCodec() { super("sagadyssey:war_dispatch_archive/v1", LIMIT, 1); }
        @Override protected UUID identity(Page page) { return PAGE_ID; }
        @Override protected CompoundTag encode(Page page) {
            var tag = new CompoundTag();
            page.previous().ifPresent(prev -> tag.put("Previous",encodeRef(prev)));
            try { tag.putByteArray("Rows",rows.write(page.records())); }
            catch (IOException e) { throw new IllegalArgumentException("cannot encode dispatch archive contents",e); }
            return tag;
        }
        @Override protected Page decode(CompoundTag tag) {
            WarHazardCodec.fields(tag,tag.contains("Previous") ? "Previous,Rows" : "Rows");
            var previous = tag.contains("Previous") ? Optional.of(decodeRef(WarHazardCodec.compound(tag,"Previous"))) : Optional.<Ref>empty();
            try {
                var records = rows.read(tag.getByteArray("Rows").orElseThrow(IllegalArgumentException::new));
                return new Page(previous,List.copyOf(records));
            } catch (IOException e) { throw new IllegalArgumentException("invalid dispatch archive contents",e); }
        }
    }
}
