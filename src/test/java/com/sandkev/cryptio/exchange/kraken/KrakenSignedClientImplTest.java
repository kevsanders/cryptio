package com.sandkev.cryptio.exchange.kraken;

import com.sandkev.cryptio.config.KrakenSpotProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies KrakenSignedClientImpl formats requests correctly:
 * - POST to private endpoint
 * - URL-encoded body with 'nonce'
 * - API-Key and API-Sign computed per spec
 */
class KrakenSignedClientImplTest {

    private WireMockServer wm;
    private KrakenSignedClientImpl client;

    // Use a base64-encoded secret (Kraken format). Keep this stable for test reproducibility.
    private final String apiKey = "test-key-123";
    private final String base64Secret = Base64.getEncoder().encodeToString("super-secret".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0); // random port
        wm.start();

        // Minimal stub for the Balance endpoint
        wm.stubFor(post(urlEqualTo("/0/private/Balance"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":[],\"result\":{\"ZUSD\":\"123.45\"}}")));

        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wm.port())
                .build();

        KrakenSpotProperties props = new KrakenSpotProperties(
                "http://localhost:" + wm.port(), // baseUrl (not strictly used by the impl if you pass WebClient)
                apiKey,
                base64Secret,
                5_000 // timeoutMs (pick any sensible value)
        );


        client = new KrakenSignedClientImpl(webClient, props);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void post_privateEndpoint_includesNonceAndValidSignature() throws Exception {
        // Arrange
        Map<String, Object> params = Map.of("asset", "ZUSD");
        var type = new ParameterizedTypeReference<Map<String, Object>>() {}; // we don't care about shape here

        // Act
        client.post("/0/private/Balance", params, type);

        // Assert request
        var requests = wm.getAllServeEvents();
        assertThat(requests).hasSize(1);

        var req = requests.get(0).getRequest();
        assertThat(req.getMethod().getName()).isEqualTo("POST");
        assertThat(req.getUrl()).isEqualTo("/0/private/Balance");
        assertThat(req.getHeader("Content-Type")).contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        assertThat(req.getHeader("API-Key")).isEqualTo(apiKey);

        // Body must be x-www-form-urlencoded with at least asset & nonce
        String body = req.getBodyAsString();
        Map<String, String> form = parseFormUrlEncoded(body);
        assertThat(form).containsEntry("asset", "ZUSD");
        assertThat(form).containsKey("nonce");
        String nonce = form.get("nonce");

        // Recompute expected API-Sign from the ACTUAL request body & path
        String path = "/0/private/Balance";
        String expectedApiSign = computeExpectedApiSign(path, nonce, body, base64Secret);

        assertThat(req.getHeader("API-Sign")).isEqualTo(expectedApiSign);
    }

    // --- helpers ---

    private static Map<String, String> parseFormUrlEncoded(String body) {
        if (body == null || body.isBlank()) return Collections.emptyMap();
        return Arrays.stream(body.split("&"))
                .map(kv -> {
                    String[] p = kv.split("=", 2);
                    String k = urlDecode(p[0]);
                    String v = p.length > 1 ? urlDecode(p[1]) : "";
                    return Map.entry(k, v);
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /**
     * Kraken spec:
     * 1) sha256 = SHA256( nonce + postData )
     * 2) message = path (bytes) + sha256
     * 3) apiSign = base64( HMAC-SHA512( base64Decode(secret), message ) )
     */
    private static String computeExpectedApiSign(String path, String nonce, String postData, String base64Secret) throws Exception {
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
    }
}
