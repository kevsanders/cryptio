package com.sandkev.cryptio.spot;

import com.sandkev.cryptio.config.KrakenSpotProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Map.entry;

@Service
public class KrakenSpotPositionsService {

    private static final String BALANCE_PATH = "/0/private/Balance";
    private final WebClient http;
    private final KrakenSpotProperties props;

    public KrakenSpotPositionsService(WebClient krakenWebClient, KrakenSpotProperties props) {
        this.http = krakenWebClient;
        this.props = props;
    }

    /**
     * Calls POST /0/private/Balance and returns { asset -> balance } for nonzero balances.
     * Kraken symbols are normalised (e.g., XXBT -> BTC, XETH -> ETH).
     */
    public Map<String, BigDecimal> fetchSpotBalances() {
        long nonce = System.currentTimeMillis();

        String postData = "nonce=" + enc(Long.toString(nonce));

        // 2) Sign correctly: SHA256( nonce + postData )
        String apiSign = sign(props.secretKey(), BALANCE_PATH, Long.toString(nonce), postData);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.post()
                    .uri(BALANCE_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("API-Key", props.apiKey().trim())
                    .header("API-Sign", apiSign)
                    .bodyValue(postData)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (body == null) return Map.of();

            // Kraken response: { "error": [], "result": { "XXBT":"0.123", "ZUSD":"15.0", ... } }
            List<?> errors = (List<?>) body.getOrDefault("error", List.of());
            if (!errors.isEmpty()) {
                throw new RuntimeException("Kraken error: " + errors);
            }

            Map<String, String> result = (Map<String, String>) body.getOrDefault("result", Map.of());

            var out = new LinkedHashMap<String, BigDecimal>(result.size());
            for (var e : result.entrySet()) {
                String k = normaliseAsset(e.getKey());
                BigDecimal v = new BigDecimal(e.getValue());
                if (v.signum() != 0) {
                    out.merge(k, v, BigDecimal::add);
                }
            }
            return out;
        } catch (WebClientResponseException e) {
            // common: 520/525 Cloudflare hiccups, 429 rate limit, 401/403 auth
            throw new RuntimeException("Kraken /0/private/Balance failed: "
                    + e.getRawStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    private static String sign(String base64Secret, String path, String nonce, String postData) {
        try {
            byte[] secret = Base64.getDecoder().decode(base64Secret.trim());

            // 2) sha256( nonce + postData )
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] sha = sha256.digest((nonce + postData).getBytes(StandardCharsets.UTF_8));

            // 3) HMAC_SHA512 over path + sha256Digest
            byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
            byte[] toSign = new byte[pathBytes.length + sha.length];
            System.arraycopy(pathBytes, 0, toSign, 0, pathBytes.length);
            System.arraycopy(sha, 0, toSign, pathBytes.length, sha.length);

            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret, "HmacSHA512"));
            byte[] sig = mac.doFinal(toSign);

            return Base64.getEncoder().encodeToString(sig);
        } catch (IllegalArgumentException badB64) {
            throw new IllegalStateException("Kraken secret is not valid base64. Re-copy your Private Key.", badB64);
        } catch (Exception e) {
            throw new IllegalStateException("Kraken signing failed", e);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    // ---- Asset normalisation (Kraken's X*/Z* prefixes to common tickers) ----
    private static final Map<String, String> ASSET_MAP = Map.ofEntries(
            entry("XXBT", "BTC"),
            entry("XBT", "BTC"),
            entry("XETH", "ETH"),
            entry("ZUSD", "USD"),
            entry("ZEUR", "EUR"),
            entry("ZGBP", "GBP"),
            entry("ZUSDT", "USDT"),
            entry("ZUSDC", "USDC")
    );
    private static final Pattern LEADING_XZ = Pattern.compile("^[XZ]");

    static String normaliseAsset(String krakenAsset) {
        if (krakenAsset == null) return null;
        if (ASSET_MAP.containsKey(krakenAsset)) return ASSET_MAP.get(krakenAsset);
        // Strip leading 'X'/'Z' if present, e.g. XREP -> REP
        return LEADING_XZ.matcher(krakenAsset).replaceFirst("");
    }
}
