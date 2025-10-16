// src/main/java/com/sandkev/cryptio/exchange/binance/ingest/RateLimit.java
package com.sandkev.cryptio.exchange.binance.ingest;

import lombok.SneakyThrows;
import java.util.Map;

public final class RateLimit {
    private RateLimit() {}
    @SneakyThrows
    public static void beforeCall(long millis) { Thread.sleep(millis); }

    /** Respect Retry-After header if the client surfaces it. */
    @SneakyThrows
    public static void afterError(Map<String,String> responseHeaders, long fallbackMs) {
        long sleep = fallbackMs;
        if (responseHeaders != null) {
            String ra = responseHeaders.getOrDefault("Retry-After", null);
            if (ra != null && ra.matches("\\d+")) sleep = Math.max(sleep, Long.parseLong(ra) * 1000L);
        }
        Thread.sleep(sleep);
    }
}
