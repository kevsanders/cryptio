package com.sandkev.cryptio.shared.http;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class HttpRetrySupport {

    private HttpRetrySupport() {}

    public static <T> T with429Retry(String path, Supplier<T> call) {
        final int maxRetries = 5;
        long backoffMs = 1_000;          // start 1s
        final long maxBackoffMs = 15_000;

        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (WebClientResponseException.TooManyRequests e) {
                if (attempt >= maxRetries) throw e;

                HttpHeaders headers = e.getHeaders();
                String retryAfterVal = headers != null ? headers.getFirst(HttpHeaders.RETRY_AFTER) : null;
                Long retryAfterMs = parseRetryAfterToMillis(retryAfterVal);

                long sleepMs = backoffMs + ThreadLocalRandom.current().nextLong(250, 750);
                if (retryAfterMs != null) sleepMs = Math.max(sleepMs, retryAfterMs);

                sleepQuietly(sleepMs);
                backoffMs = Math.min((long)(backoffMs * 1.8), maxBackoffMs);
            }
        }
    }

    public static Long parseRetryAfterToMillis(String v) {
        if (v == null || v.isBlank()) return null;
        // numeric seconds
        try { return Long.parseLong(v.trim()) * 1000L; } catch (NumberFormatException ignore) {}
        // HTTP-date
        try {
            long target = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            long delta = target - System.currentTimeMillis();
            return Math.max(delta, 0L);
        } catch (Exception ignore) {}
        return null;
    }

    public static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}

