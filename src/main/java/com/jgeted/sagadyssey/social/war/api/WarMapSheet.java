package com.jgeted.sagadyssey.social.war.api;

import java.util.Objects;

/** Metadata of one real held vanilla map; pixels do not carry client-authoritative world coordinates. */
public record WarMapSheet(int id, WarKey dimension, int centerX, int centerZ, int scale) {
    public WarMapSheet {
        Objects.requireNonNull(dimension);
        if (id < 0 || scale < 0 || scale > 4 || Math.abs((long) centerX) > 30_000_000
                || Math.abs((long) centerZ) > 30_000_000) throw new IllegalArgumentException("invalid map sheet");
    }
    public int x(int pixel) { return coordinate(centerX, pixel); }
    public int z(int pixel) { return coordinate(centerZ, pixel); }
    private int coordinate(int center, int pixel) {
        if (pixel < 0 || pixel > 127) throw new IllegalArgumentException("map pixel outside sheet");
        int block = Math.addExact(center, (pixel - 64) * (1 << scale) + (1 << scale) / 2);
        if (Math.abs((long) block) > 29_999_984) throw new IllegalArgumentException("map target outside world");
        return block;
    }
    public double pixelX(int block) { return ((long) block - centerX) / (double) (1 << scale) + 64; }
    public double pixelZ(int block) { return ((long) block - centerZ) / (double) (1 << scale) + 64; }
}
