package com.sandkev.cryptio.trades;

// src/main/java/com/sandkev/cryptio/binance/BinanceSignedClient.java
//package com.sandkev.cryptio.binance;

import com.sandkev.cryptio.config.BinanceSpotProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class BinanceSignedClient {

    private final WebClient http;
    private final BinanceSpotProperties props;

    public BinanceSignedClient(WebClient binanceWebClient, BinanceSpotProperties props) {
        this.http = binanceWebClient;   // must have X-MBX-APIKEY default header
        this.props = props;
    }

    /** Build canonical query string (stable order) and sign it; returns full path+query+sig for GET. */
    public String signedGetPath(String path, LinkedHashMap<String,String> params) {
        long ts = System.currentTimeMillis();
        params.put("timestamp", String.valueOf(ts));
        params.put("recvWindow", String.valueOf(props.recvWindow()));

        String qs = canonicalQuery(params);
        String sig = hmacSHA256Hex(qs);
        return path + "?" + qs + "&signature=" + sig;
    }

    public <T> T getJson(String signedPath, Class<T> bodyType, String onErrorLabel) {
        return http.get()
                .uri(signedPath)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(s -> s.value() >= 400, r -> r.bodyToMono(String.class).map(body ->
                        new RuntimeException("Binance <endpoint> error " + r.statusCode().value() + ": " + body)))
//                .onStatus(s -> s.value() >= 400, r -> r.bodyToMono(String.class).map(body ->
//                        new RuntimeException(onErrorLabel + " " + r.statusCode().value() + ": " + body)))
                .bodyToMono(bodyType)
                .block();
    }

    // --- helpers ---
    private static String canonicalQuery(LinkedHashMap<String,String> params) {
        StringBuilder sb = new StringBuilder(128);
        boolean first = true;
        for (var e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append('&'); first = false;
            sb.append(e.getKey()).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String hmacSHA256Hex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sig.length * 2);
            for (byte b : sig) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    public <T> T getJsonMap(String signedPath) {
        @SuppressWarnings("unchecked")
        T out = (T) http.get().uri(signedPath).retrieve()
                .bodyToMono(Map.class).block();
        return out;
    }

    public <T> T getJsonList(String signedPath) {
        @SuppressWarnings("unchecked")
        T out = (T) http.get().uri(signedPath).retrieve()
                .bodyToMono(List.class).block();
        return out;
    }

}
