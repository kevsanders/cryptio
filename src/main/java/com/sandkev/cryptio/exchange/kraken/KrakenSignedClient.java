package com.sandkev.cryptio.exchange.kraken;

// src/main/java/com/sandkev/cryptio/binance/BinanceSignedClient.java
//package com.sandkev.cryptio.binance;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Component
public class KrakenSignedClient {
    private final WebClient http; // baseUrl https://api.kraken.com
    private final com.sandkev.cryptio.config.KrakenSpotProperties props;

    public KrakenSignedClient(WebClient krakenWebClient, com.sandkev.cryptio.config.KrakenSpotProperties props) {
        this.http = krakenWebClient;
        this.props = props;
    }

    public String signedPostPath(String path, LinkedHashMap<String,String> form) throws InvalidKeyException, NoSuchAlgorithmException {
        // nonce is required and must be increasing
        String nonce = String.valueOf(System.currentTimeMillis());
        form.put("nonce", nonce);

        String postData = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        // message = path + SHA256(nonce + postData)
        byte[] sha256 = MessageDigest.getInstance("SHA-256")
                .digest((nonce + postData).getBytes(StandardCharsets.UTF_8));
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[pathBytes.length + sha256.length];
        System.arraycopy(pathBytes, 0, message, 0, pathBytes.length);
        System.arraycopy(sha256, 0, message, pathBytes.length, sha256.length);

        // signature = HMAC-SHA512(base64Decode(secret), message), then base64-encode
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(props.secretKey()), "HmacSHA512"));
        String apiSign = Base64.getEncoder().encodeToString(mac.doFinal(message));

        // You can POST to 'path' with headers { API-Key, API-Sign } and body = postData
        // Keep the return 'path' handy if you like, but typically you directly post here.
        return path + "?" + postData; // optional (for logging)
    }

    public <T> T postJson(String pathWithQuery, Class<T> bodyType, String onErrorLabel) {
        // extract path and body; or adjust to pass path & form separately
        String[] ps = pathWithQuery.split("\\?", 2);
        String path = ps[0];
        String body = ps.length > 1 ? ps[1] : "";
        String apiSign = ""; //TODO:

        return http.post()
                .uri(path)
                .header("API-Key", props.apiKey())
                .header("API-Sign", /* computed signature as above */ apiSign)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.value() >= 400, r -> r.bodyToMono(String.class).map(err ->
                        new RuntimeException(onErrorLabel + " " + r.statusCode().value() + ": " + err)))
                .bodyToMono(bodyType)
                .block();
    }
}
