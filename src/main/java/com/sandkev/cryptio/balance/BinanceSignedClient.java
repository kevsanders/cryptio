package com.sandkev.cryptio.balance;

import com.sandkev.cryptio.config.BinanceSpotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RequiredArgsConstructor
@Slf4j
public class BinanceSignedClient {

    private final @Qualifier("binanceClient") WebClient binanceClient;   // from BinanceSpotProperties @Qualifier("binanceWebClient")
    private final BinanceSpotProperties props;

    /* ---------- New canonical API ---------- */

//    @Override
    public <T> T get(String path,
                     Map<String, Object> params,
                     ParameterizedTypeReference<T> bodyType) {

        // 1) Build params in insertion order, just like your old code
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        if (params != null) {
            for (var e : params.entrySet()) {
                if (e.getValue() != null) p.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        long ts = System.currentTimeMillis();
        p.put("timestamp", String.valueOf(ts));
        p.put("recvWindow", String.valueOf(60_000));

        // 2) URL-encode VALUES and build the canonical query (same as your working version)
        String qs = canonicalQuery(p);

        // 3) Sign the EXACT encoded query string you’re going to send
        String sig = hmacSha256Hex(props.secretKey(), qs);

        // 4) Send the SAME bytes (relative path is fine if your WebClient has baseUrl)
        String uri = path + "?" + qs + "&signature=" + sig;

        return binanceClient.get()
                .uri(uri) // NOTE: keep as String, not builder with queryParam (which would re-encode)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-MBX-APIKEY", props.apiKey())
                .retrieve()
                .onStatus(s -> s.isError(), resp ->
                        resp.bodyToMono(String.class).map(body ->
                                new RuntimeException("Binance " + path + " error " + resp.statusCode().value() + ": " + body)))
                .bodyToMono(bodyType)
                .block(Duration.ofSeconds(30));
    }

    // --- helpers (identical behavior to your old service) ---

    private static String canonicalQuery(LinkedHashMap<String, String> params) {
        StringBuilder sb = new StringBuilder(128);
        boolean first = true;
        for (var e : params.entrySet()) {
            String v = e.getValue();
            if (v == null) continue;
            if (!first) sb.append('&'); else first = false;
            sb.append(e.getKey()).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char a = s.charAt(0), b = s.charAt(s.length() - 1);
        if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }


    /** Signed GET returning raw JSON. Adds timestamp & recvWindow automatically. */
    public Mono<String> get(String path, Map<String, String> params) {
        var qp = new LinkedHashMap<String,String>(params == null ? 4 : params.size() + 2);
        long ts = Instant.now().toEpochMilli();
        long recv = props.recvWindow() <= 0 ? 5000L : props.recvWindow();

        qp.put("timestamp", String.valueOf(ts));
        qp.put("recvWindow", String.valueOf(recv));
        if (params != null) params.forEach((k,v) -> { if (v != null) qp.put(k, v); });

        String qs = canonicalQuery(qp);
        String sig = hmacSHA256Hex(qs, props.secretKey());

        String fullQuery = qs + "&signature=" + sig;

        return binanceClient.get()
                .uri(uri -> uri.path(path).query(fullQuery).build())
                .header("X-MBX-APIKEY", props.apiKey())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(WebClientResponseException.class, e ->
                        new RuntimeException("Binance GET " + path + " failed: "
                                + e.getRawStatusCode() + " " + e.getResponseBodyAsString(), e));
    }

    /* ---------- Legacy compatibility (kept so old callers still work) ---------- */

    /** Legacy: build signed path (path?query&signature=...). Prefer {@link #get(String, Map)}. */
    public String signedGetPath(String path, LinkedHashMap<String,String> params) {
        if (params == null) params = new LinkedHashMap<>();
        params.put("timestamp", String.valueOf(Instant.now().toEpochMilli()));
        long recv = props.recvWindow() <= 0 ? 5000L : props.recvWindow();
        params.put("recvWindow", String.valueOf(recv));

        String qs = canonicalQuery(params);
        String sig = hmacSHA256Hex(qs, props.secretKey());
        return path + "?" + qs + "&signature=" + sig;
    }

    /** Legacy: execute a pre-signed GET and parse JSON to a given type (blocking). */
    public <T> T getJson(String signedPath, Class<T> bodyType) {
        return binanceClient.get()
                .uri(signedPath)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(bodyType)
                .onErrorMap(WebClientResponseException.class, e ->
                        new RuntimeException("Binance GET " + signedPath + " failed: "
                                + e.getRawStatusCode() + " " + e.getResponseBodyAsString(), e))
                .block();
    }

    /** Legacy helpers kept for minimal diff in existing code. */
    @SuppressWarnings("unchecked")
    public <T> T getJsonMap(String signedPath) {
        return (T) binanceClient.get().uri(signedPath).retrieve().bodyToMono(Map.class).block();
    }
    @SuppressWarnings("unchecked")
    public <T> T getJsonList(String signedPath) {
        return (T) binanceClient.get().uri(signedPath).retrieve().bodyToMono(List.class).block();
    }

    /* ---------- Internals ---------- */
    private static String toQueryString(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(128);
        boolean first = true;
        for (var e : params.entrySet()) {
            Object val = e.getValue();
            if (val == null) continue;
            if (!first) sb.append('&'); else first = false;
            sb.append(e.getKey()).append('=').append(encode(String.valueOf(val)));
        }
        return sb.toString();
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
    private static String canonicalQuery(Map<String,String> params) {
        // Preserve caller order if LinkedHashMap; otherwise sort keys for stability.
        Collection<Map.Entry<String,String>> entries =
                (params instanceof LinkedHashMap) ? params.entrySet()
                        : new TreeMap<>(params).entrySet();

        StringBuilder sb = new StringBuilder(128);
        boolean first = true;
        for (var e : entries) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || v == null) continue;
            if (!first) sb.append('&'); else first = false;
            sb.append(k).append('=').append(encode(v));
        }
        return sb.toString();
    }

    private static String hmacSHA256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sig.length * 2);
            for (byte b : sig) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }


//    getSpotBalances
}
