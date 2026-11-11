package com.jgeted.sagadyssey.worldsim;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class LegacySavedDataLoadTest {
    @Test
    void allFrozenSavedDataFilesLoadAndProduceStableBaselines() throws Exception {
        Map<String, MessageDigest> worldDigests = new TreeMap<>();
        try (BufferedReader manifest = new BufferedReader(new InputStreamReader(
                requireResource("fixtures/v1_9/manifest.sha256"), StandardCharsets.UTF_8))) {
            for (String line : manifest.lines().filter(value -> !value.isBlank()).toList()) {
                String path = line.split("  ", 2)[1];
                String world = path.substring(0, path.indexOf('/'));
                CompoundTag root;
                try (InputStream stream = requireResource("fixtures/v1_9/" + path)) {
                    root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
                }
                assertTrue(MinecraftApi.containsTag(root, "data", Tag.TAG_COMPOUND), path + " has no SavedData root");
                MessageDigest digest = worldDigests.computeIfAbsent(world, ignored -> sha256());
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                digest.update(root.getCompoundOrEmpty("data").toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        assertEquals(3, worldDigests.size());
        Map<String, String> first = finish(worldDigests);
        assertEquals(first, loadAgain(), "Legacy baseline report changed between identical scans");
    }

    private static Map<String, String> loadAgain() throws Exception {
        Map<String, MessageDigest> digests = new TreeMap<>();
        try (BufferedReader manifest = new BufferedReader(new InputStreamReader(
                requireResource("fixtures/v1_9/manifest.sha256"), StandardCharsets.UTF_8))) {
            for (String line : manifest.lines().filter(value -> !value.isBlank()).toList()) {
                String path = line.split("  ", 2)[1];
                String world = path.substring(0, path.indexOf('/'));
                CompoundTag root;
                try (InputStream stream = requireResource("fixtures/v1_9/" + path)) {
                    root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
                }
                MessageDigest digest = digests.computeIfAbsent(world, ignored -> sha256());
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                digest.update(root.getCompoundOrEmpty("data").toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        return finish(digests);
    }

    private static Map<String, String> finish(Map<String, MessageDigest> digests) {
        Map<String, String> result = new TreeMap<>();
        digests.forEach((world, digest) -> result.put(world,
                HexFormat.of().formatHex(digest.digest())));
        return result;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static InputStream requireResource(String path) {
        InputStream stream = LegacySavedDataLoadTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing fixture resource: " + path);
        return stream;
    }
}
