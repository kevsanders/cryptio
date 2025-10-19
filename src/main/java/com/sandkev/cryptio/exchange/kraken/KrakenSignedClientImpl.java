package com.sandkev.cryptio.exchange.kraken;

import com.sandkev.cryptio.config.KrakenClientConfig.KrakenClientProperties;
import com.sandkev.cryptio.shared.http.HttpRetrySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Kraken private endpoints must be POST with form urlencoded body.
 * Signature: API-Sign = base64( HMAC-SHA512( base64Decode(secret),
 *                                 path + SHA256(nonce + POSTDATA) ) )
 * Headers: API-Key, API-Sign
 */
@Slf4j
@RequiredArgsConstructor
public class KrakenSignedClientImpl implements KrakenSignedClient {

    // Prefer naming this bean explicitly in your @Configuration
    // e.g., @Bean(name="krakenWebClient") WebClient ...
    private final WebClient krakenWebClient;
    private final KrakenClientProperties props;

    // ---------- SignedClient API ----------

    @Override
    public <T> T get(String path, Map<String, Object> params, ParameterizedTypeReference<T> bodyType) {
        // Kraken private "GET" isn’t a thing; route to POST semantics to avoid breaking callers.
        // If caller points to a public path (/0/public/...), we’ll send a GET unsigned instead.
        if (isPublicPath(path)) {
            return doPublicGet(path, params, bodyType);
        }
        return post(path, params, bodyType);
    }

    @Override
    public <T> T post(String path, Map<String, Object> params, ParameterizedTypeReference<T> bodyType) {
        return HttpRetrySupport.with429Retry(path, () -> doSignedPost(path, params, bodyType));
    }

    @Override
    public <T> T getPublic(String path, Map<String, Object> params, ParameterizedTypeReference<T> bodyType) {
        return doPublicGet(path, params, bodyType);
    }

    // ---------- Impl details ----------

    private boolean isPublicPath(String path) {
        // Conventional Kraken paths: /0/public/* vs /0/private/*
        // Be tolerant of missing leading slash.
        String p = path.startsWith("/") ? path : ("/" + path);
        return p.startsWith("/0/public/");
    }

    private <T> T doPublicGet(String path, Map<String, Object> params, ParameterizedTypeReference<T> type) {
        var qpm = toQueryParams(params);
        log.info("Kraken public GET: {} {}", path, qpm);
        return krakenWebClient.get()
                .uri(u -> u.path(path).queryParams(qpm).build())
                .retrieve()
                .onStatus(s -> s.value() >= 400, r -> r.bodyToMono(String.class)
                        .map(body -> new RuntimeException("Kraken " + path + " error " + r.statusCode().value() + ": " + body)))
                .bodyToMono(type)
                .block();
    }

    private <T> T doSignedPost(String path, Map<String, Object> params, ParameterizedTypeReference<T> type) {
        String nonce = KrakenNonce.next();

        // Build ordered form fields (Kraken is sensitive to exact postdata in signature)
        var form = new LinkedHashMap<String, String>();
        if (params != null) {
            params.forEach((k, v) -> { if (v != null) form.put(k, String.valueOf(v)); });
        }
        form.put("nonce", nonce);

        String postData = urlEncodeForm(form);

        // Signature needs the *path as sent on wire* (Kraken expects the literal path, e.g. "/0/private/Balance")
        String canonicalPath = path.startsWith("/") ? path : ("/" + path);
        String apiSign = computeKrakenApiSign(canonicalPath, nonce, postData, props.secretKey());

        log.info("Kraken signed POST: {} fields={}", path, form.keySet());

        return krakenWebClient.post()
                .uri(canonicalPath)
                .header("API-Key", props.apiKey())
                .header("API-Sign", apiSign)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(postData)
                .retrieve()
                .onStatus(s -> s.value() >= 400, r -> r.bodyToMono(String.class)
                        .map(body -> new RuntimeException("Kraken " + path + " error " + r.statusCode().value() + ": " + body)))
                .bodyToMono(type)
                .block();
    }

    private static MultiValueMap<String, String> toQueryParams(@Nullable Map<String, Object> params) {
        var qpm = new LinkedMultiValueMap<String, String>();
        if (params != null) {
            params.forEach((k, v) -> { if (v != null) qpm.add(k, String.valueOf(v)); });
        }
        return qpm;
    }

    private static String urlEncodeForm(LinkedHashMap<String, String> form) {
        return form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String computeKrakenApiSign(String path, String nonce, String postData, String base64Secret) {
        try {
            byte[] sha256 = MessageDigest.getInstance("SHA-256")
                    .digest((nonce + postData).getBytes(StandardCharsets.UTF_8));

            byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
            byte[] message = new byte[pathBytes.length + sha256.length];
            System.arraycopy(pathBytes, 0, message, 0, pathBytes.length);
            System.arraycopy(sha256, 0, message, pathBytes.length, sha256.length);

            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Secret), "HmacSHA512"));
            byte[] sig = mac.doFinal(message);
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("Kraken API-Sign computation failed", e);
        }
    }
}
