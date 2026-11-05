package com.jgeted.sagadyssey.social.logistics;

import java.util.Map;

public record StockSnapshot(Map<String, Integer> items, int readableWarehouses,
                            int unavailableWarehouses) {}
