package com.sandkev.cryptio.balance;

import com.sandkev.cryptio.config.BinanceClientConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class BinanceSignedClientImpl implements BinanceSignedClient {

    private final @Qualifier("binanceClient") WebClient client;
    private final BinanceClientConfig.BinanceClientProperties props;

    /**
     * Signed GET that:
     *  - adds timestamp & recvWindow
     *  - URL-encodes values (exactly once)
     *  - signs the exact query string sent
     *  - blocks with props.timeoutMs()
     */
    @Override
    public <T> T get(String path,
                     Map<String, Object> params,
                     ParameterizedTypeReference<T> bodyType) {

        final int maxRetries = 5;
        long backoffMs = 1_000; // start 1s
        final long maxBackoffMs = 15_000;

        for (int attempt = 0; ; attempt++) {
            try {
                return doSignedGet(path, params, bodyType); // your existing code path
            } catch (WebClientResponseException.TooManyRequests e) {
                if (attempt >= maxRetries) throw e;

                HttpHeaders headers = e.getHeaders(); // <- use getHeaders()
                String retryAfterVal = headers != null ? headers.getFirst(HttpHeaders.RETRY_AFTER) : null;

                long sleepMs = backoffMs + ThreadLocalRandom.current().nextLong(250, 750);
                Long retryAfterMs = parseRetryAfterToMillis(retryAfterVal);
                if (retryAfterMs != null) {
                    sleepMs = Math.max(sleepMs, retryAfterMs);
                }

                String used1m = headers != null ? headers.getFirst("X-MBX-USED-WEIGHT-1m") : null;
                log.warn("429 {} attempt {}/{}; X-MBX-USED-WEIGHT-1m={}; sleeping {} ms",
                        path, attempt + 1, maxRetries, used1m, sleepMs);

                sleepQuietly(sleepMs);
                backoffMs = Math.min((long)(backoffMs * 1.8), maxBackoffMs);
                continue;
            }
        }
    }

    @Nullable
    private static Long parseRetryAfterToMillis(@Nullable String v) {
        if (v == null || v.isBlank()) return null;
        // Numeric seconds
        try { return Long.parseLong(v.trim()) * 1000L; } catch (NumberFormatException ignore) {}
        // HTTP-date (e.g., Wed, 21 Oct 2015 07:28:00 GMT)
        try {
            return java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - System.currentTimeMillis();
        } catch (Exception ignore) {}
        return null;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private  <T> T doSignedGet(String path, Map<String,Object> params, ParameterizedTypeReference<T> type) {
        var qp = sign(params);
        log.info("query binance: {} / {}", path, params);
        return client.get()
                .uri(uri -> uri.path(path).queryParams(qp).build())
                .retrieve()
                .onStatus(s -> s.value() >= 400, r -> r.bodyToMono(String.class)
                        .map(body -> new RuntimeException("Binance " + path + " error " + r.statusCode().value() + ": " + body)))
                .bodyToMono(type)
                .block();
    }

    @Override
    public <T> T post(String path, Map<String,Object> params, ParameterizedTypeReference<T> type) {
        var qp = sign(params); // Binance accepts signed params in the query string for POST
        return client.post()
                .uri(uri -> uri.path(path).queryParams(qp).build())
                .retrieve()
                .onStatus(s -> s.value() >= 400, r -> r.bodyToMono(String.class)
                        .map(body -> new RuntimeException("Binance " + path + " error " + r.statusCode().value() + ": " + body)))
                .bodyToMono(type)
                .block();
    }

    @Override
    public <T> T getPublic(String path, Map<String,Object> params, ParameterizedTypeReference<T> type) {
        var qpm = new org.springframework.util.LinkedMultiValueMap<String,String>();
        if (params != null) {
            params.forEach((k,v) -> { if (v != null) qpm.add(k, String.valueOf(v)); });
        }
        return client.get()
                .uri(u -> u.path(path).queryParams(qpm).build())
                .retrieve()
                .onStatus(s -> s.value() >= 400, r -> r.bodyToMono(String.class)
                        .map(body -> new RuntimeException("Binance " + path + " error " + r.statusCode().value() + ": " + body)))
                .bodyToMono(type)
                .block();
    }

    /* -------------------- helpers -------------------- */

    private MultiValueMap<String,String> sign(Map<String,Object> params) {
        long ts = System.currentTimeMillis();
        long recvWindow = props.recvWindow();
        var ordered = new LinkedHashMap<String,Object>();
        if (params != null) ordered.putAll(params);
        ordered.put("timestamp", ts);
        ordered.put("recvWindow", recvWindow);

        var qs = ordered.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        String sig = hmacSha256(qs, props.secretKey());

        var qpm = new LinkedMultiValueMap<String,String>();
        ordered.forEach((k,v) -> { if (v != null) qpm.add(k, String.valueOf(v)); });
        qpm.add("signature", sig);
        return qpm;
    }

    private static String hmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(sig.length * 2);
            for (byte b : sig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

}
