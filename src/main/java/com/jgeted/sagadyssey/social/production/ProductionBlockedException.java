package com.jgeted.sagadyssey.social.production;

/** 可重试的生产阻塞；批次和原料预留保持有效，不进入失败终态。 */
public final class ProductionBlockedException extends IllegalStateException {
    private final String code;

    public ProductionBlockedException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
