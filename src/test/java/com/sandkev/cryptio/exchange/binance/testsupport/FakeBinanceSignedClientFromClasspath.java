package com.sandkev.cryptio.binance.testsupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandkev.cryptio.balance.BinanceSignedClient;
import org.springframework.core.ParameterizedTypeReference;

import java.io.InputStream;
import java.util.Map;

public class FakeBinanceSignedClientFromClasspath implements BinanceSignedClient {
    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, String> pathToResource;

    public FakeBinanceSignedClientFromClasspath(Map<String, String> pathToResource) {
        this.pathToResource = pathToResource;
    }

    @Override
    public <T> T get(String path, Map<String, Object> params, ParameterizedTypeReference<T> typeRef) {
        String res = pathToResource.get(path);
        if (res == null) throw new IllegalArgumentException("No resource mapped for path: " + path);
        try (InputStream is = getClass().getResourceAsStream(res)) {
            if (is == null) throw new IllegalStateException("Resource not found on classpath: " + res);
            // Simple heuristic: if expecting a List, read as List; else Map
            if (typeRef.getType().getTypeName().contains("List")) {
                return om.readValue(is, new TypeReference<T>() {});
            } else {
                return om.readValue(is, new TypeReference<T>() {});
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed reading " + res + " for " + path, e);
        }
    }
}
