// src/test/java/com/sandkev/cryptio/exchange/binance/testsupport/FakeBinanceSignedClientFromClasspath.java
package com.sandkev.cryptio.exchange.binance.testsupport;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sandkev.cryptio.balance.BinanceSignedClient;
import org.springframework.core.ParameterizedTypeReference;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FakeBinanceSignedClientFromClasspath implements BinanceSignedClient {

    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, String> pathToResource;

    // remember if we've already served a non-empty response for a given path
    private final Set<String> servedOnce = ConcurrentHashMap.newKeySet();

    public FakeBinanceSignedClientFromClasspath(Map<String, String> pathToResource) {
        this.pathToResource = pathToResource;
    }

    @Override
    public <T> T get(String path, Map<String, Object> params, ParameterizedTypeReference<T> typeRef) {
        return readOnceThenEmpty(path, typeRef);
    }

    @Override
    public <T> T post(String path, Map<String, Object> params, ParameterizedTypeReference<T> bodyType) {
        return readOnceThenEmpty(path, bodyType);
    }

    @Override
    public <T> T getPublic(String path, Map<String, Object> params, ParameterizedTypeReference<T> typeRef) {
        return readOnceThenEmpty(path, typeRef);
    }

    private <T> T readOnceThenEmpty(String path, ParameterizedTypeReference<T> typeRef) {
        // first call for this path: serve the real fixture
        if (servedOnce.add(path)) {
            return readFromClasspath(path, typeRef);
        }
        // subsequent calls: serve an empty payload with the correct shape so paginators stop
        String emptyJson = emptyPayloadForPath(path);
        return readFromStream(new ByteArrayInputStream(emptyJson.getBytes(StandardCharsets.UTF_8)), typeRef, "(synthetic empty)");
    }

    private String emptyPayloadForPath(String path) {
        // time-window endpoints with object payloads:
        if (path.endsWith("/sapi/v1/convert/tradeFlow"))           return "{\"rows\":[]}";
        if (path.endsWith("/sapi/v1/asset/assetDividend"))         return "{\"rows\":[]}";
        if (path.endsWith("/sapi/v1/asset/dribblet"))              return "{\"userAssetDribblets\":[]}";

        // time-window list endpoints:
        if (path.endsWith("/sapi/v1/capital/deposit/hisrec"))      return "[]";
        if (path.endsWith("/sapi/v1/capital/withdraw/history"))    return "[]";

        // id-cursor list endpoint:
        if (path.endsWith("/api/v3/myTrades"))                     return "[]";

        // default to empty array
        return "[]";
    }

    private <T> T readFromClasspath(String path, ParameterizedTypeReference<T> typeRef) {
        String res = pathToResource.get(path);
        if (res == null) {
            throw new IllegalArgumentException("No resource mapped for path: " + path);
        }
        try (InputStream is = getClass().getResourceAsStream(res)) {
            if (is == null) {
                throw new IllegalStateException("Resource not found on classpath: " + res + " for path " + path);
            }
            return readFromStream(is, typeRef, res);
        } catch (Exception e) {
            throw new RuntimeException("Failed reading " + res + " for " + path, e);
        }
    }

    private <T> T readFromStream(InputStream is, ParameterizedTypeReference<T> typeRef, String source) {
        try {
            TypeFactory tf = om.getTypeFactory();
            JavaType jt = tf.constructType(typeRef.getType());
            return om.readValue(is, jt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize " + source, e);
        }
    }
}
