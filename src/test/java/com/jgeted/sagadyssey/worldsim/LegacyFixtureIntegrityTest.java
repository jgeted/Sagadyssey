package com.jgeted.sagadyssey.worldsim;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LegacyFixtureIntegrityTest {
    @Test
    void allThreeLegacyWorldSnapshotsMatchFrozenChecksums() throws Exception {
        String manifest = resourceText("fixtures/v1_9/manifest.sha256");
        Set<String> worlds = new HashSet<>();
        int checked = 0;
        for (String line : manifest.lines().filter(value -> !value.isBlank()).toList()) {
            String[] fields = line.split("  ", 2);
            assertEquals(2, fields.length, "Malformed fixture manifest line: " + line);
            String path = fields[1];
            worlds.add(path.substring(0, path.indexOf('/')));
            assertEquals(fields[0], resourceSha256("fixtures/v1_9/" + path), path);
            checked++;
        }
        assertEquals(Set.of("worker", "farmer", "blacksmith"), worlds);
        assertEquals(30, checked);
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream stream = requireResource(path)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String resourceSha256(String path) throws IOException, NoSuchAlgorithmException {
        try (InputStream stream = requireResource(path)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(stream.readAllBytes()));
        }
    }

    private static InputStream requireResource(String path) {
        InputStream stream = LegacyFixtureIntegrityTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing fixture resource: " + path);
        return stream;
    }
}
