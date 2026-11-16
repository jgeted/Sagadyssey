package com.jgeted.sagadyssey.social.war.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** 已完成写入的内容寻址归档；不把任意文件路径存入领域模型。 */
public record WarArchiveRef(long generation, String sha256, int bytes, long checkpointTick) {
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    public WarArchiveRef {
        Objects.requireNonNull(sha256, "sha256");
        if (generation < 0 || !HASH.matcher(sha256).matches() || bytes < 44 || checkpointTick < 0) {
            throw new IllegalArgumentException("invalid war archive reference");
        }
    }
}
