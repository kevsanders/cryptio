package com.sandkev.cryptio.balance;

import com.sandkev.cryptio.config.BinanceClientConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
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
/*
    @Override
    public <T> T get(String path,
                     Map<String, Object> params,
                     ParameterizedTypeReference<T> bodyType) {

        // Use LinkedHashMap to preserve caller order if provided, else sort for stability
        final Map<String, String> qp = params instanceof LinkedHashMap
                ? new LinkedHashMap<>()
                : new TreeMap<>();

        if (params != null) {
            for (var e : params.entrySet()) {
                final Object v = e.getValue();
                if (v != null) qp.put(e.getKey(), String.valueOf(v));
            }
        }

        // Add required auth params
        qp.put("timestamp", String.valueOf(System.currentTimeMillis()));
        long recv = props.recvWindow() > 0 ? props.recvWindow() : 5000L;
        qp.put("recvWindow", String.valueOf(recv));

        // Build canonical query and signature
        String qs = canonicalQuery(qp);
        String sig = hmacSHA256Hex(qs, props.secretKey());

        String uri = path + "?" + qs + "&signature=" + sig;

        try {
            return binanceClient.get()
                    .uri(uri) // keep as raw string to avoid re-encoding
                    .accept(MediaType.APPLICATION_JSON)
                    // if your WebClient already sets X-MBX-APIKEY globally, this is redundant but harmless:
                    .header("X-MBX-APIKEY", props.apiKey())
                    .retrieve()
                    .bodyToMono(bodyType)
                    .timeout(Duration.ofMillis(Math.max(1000, props.timeoutMs())))
                    .block();
        } catch (WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            throw new RuntimeException("Binance GET " + path + " failed: " +
                    e.getRawStatusCode() + " " + body, e);
        }
    }


    private static String canonicalQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder(128);
        boolean first = true;
        for (var e : params.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || v == null) continue;
            if (!first) sb.append('&'); else first = false;
            sb.append(k).append('=').append(encode(v));
        }
        return sb.toString();
    }

    private static String encode(String v) {
        // Binance wants standard URL encoding; replace '+' with %20 is not necessary
        // if we keep the same bytes for signing and request.
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
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

*/



    @Override
    public <T> T get(String path, Map<String,Object> params, ParameterizedTypeReference<T> type) {
        var qp = sign(params);
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
