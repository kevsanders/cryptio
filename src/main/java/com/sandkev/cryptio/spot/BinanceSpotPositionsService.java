package com.sandkev.cryptio.spot;

import com.sandkev.cryptio.config.BinanceSpotProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class BinanceSpotPositionsService {

    private final WebClient http;
    private final BinanceSpotProperties props;

    public BinanceSpotPositionsService(WebClient binanceWebClient, BinanceSpotProperties props) {
        this.http = binanceWebClient;
        this.props = props;
    }

    /**
     * Calls GET /api/v3/account and returns { asset -> free+locked } for nonzero balances.
     */
    public Map<String, BigDecimal> fetchSpotBalances() {
        long timestamp = Instant.now().toEpochMilli();
        String query = "timestamp=" + timestamp + "&recvWindow=" + props.recvWindow();
        String signature = hmacSha256(query, props.secretKey());

        try {
            var body = http.get()
                    .uri(uri -> uri.path("/api/v3/account")
                            .queryParam("timestamp", timestamp)
                            .queryParam("recvWindow", props.recvWindow())
                            .queryParam("signature", signature)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (body == null) return Map.of();

            // balances is an array of { asset, free, locked }
            var balances = (List<Map<String, String>>) body.getOrDefault("balances", List.of());
            var out = new LinkedHashMap<String, BigDecimal>(balances.size());
            for (var b : balances) {
                String asset = b.get("asset");
                BigDecimal free = new BigDecimal(b.getOrDefault("free", "0"));
                BigDecimal locked = new BigDecimal(b.getOrDefault("locked", "0"));
                BigDecimal total = free.add(locked);
                if (total.signum() != 0) {
                    out.put(asset, total);
                }
            }
            return out;
        } catch (WebClientResponseException e) {
            // Common causes: invalid API permissions, timestamp skew, IP restrictions, 429
            throw new RuntimeException("Binance /api/v3/account failed: " + e.getRawStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    private static String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            // hex
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }

    // Utility if you need to safely encode additional params later
    @SuppressWarnings("unused")
    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

/*
    // Example adapter -> your HoldingRepo
    // Call this in a job/controller to upsert holdings
    public void syncSpotHoldings(HoldingRepo holdings) {
        var balances = fetchSpotBalances();
        balances.forEach((asset, qty) -> {
            // upsert by asset; simplified sample
            var existing = holdings.findAll().stream()
                    .filter(h -> h.getAsset().equals(asset))
                    .findFirst()
                    .orElseGet(() -> holdings.save(new Holding(asset, BigDecimal.ZERO, BigDecimal.ZERO)));
            existing.setQuantity(qty);
            holdings.save(existing);
        });
    }
*/

}

