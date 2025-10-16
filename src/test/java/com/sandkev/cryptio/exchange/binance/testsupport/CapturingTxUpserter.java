package com.sandkev.cryptio.exchange.binance.testsupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.tx.TxUpserter;
import org.springframework.core.ParameterizedTypeReference;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public class CapturingTxUpserter implements TxUpserter {

    Set<String> keys = new HashSet<>();

    public static record Tx(
            String exchange, String accountRef, String base, String quote, String type,
            BigDecimal quantity, BigDecimal price, BigDecimal fee, String feeAsset,
            Instant ts, String externalId
    ){}
    private final List<Tx> calls = new ArrayList<>();

    @Override public int upsert(String exchange, String accountRef, String base, String quote, String type,
                                BigDecimal quantity, BigDecimal price, BigDecimal fee, String feeAsset,
                                Instant ts, String externalId) {

        if(keys.add(exchange + "." + externalId)) {
            calls.add(new Tx(exchange, accountRef, base, quote, type, quantity, price, fee, feeAsset, ts, externalId));
            return 1;
        } else {
            return 0;
        }
    }
    public List<Tx> calls() { return calls; }


    @Override
    public com.sandkev.cryptio.domain.Tx convertTx(String exchange, String accountRef, String asset, String dir, BigDecimal qty, Instant ts, String orderId) {
        return null;
    }
    @Override
    public int write(com.sandkev.cryptio.domain.Tx tx) {
        return 0;
    }

    public static class FakeBinanceSignedClientFromClasspath implements BinanceSignedClient {
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

        @Override
        public <T> T post(String path, Map<String, Object> params, ParameterizedTypeReference<T> bodyType) {
            return null;
        }

        @Override
        public <T> T getPublic(String path, Map<String, Object> params, ParameterizedTypeReference<T> bodyType) {
            return null;
        }
    }
}
