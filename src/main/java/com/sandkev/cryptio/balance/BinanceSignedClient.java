package com.sandkev.cryptio.balance;

import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;

public interface BinanceSignedClient {
    <T> T get (String path, Map<String, Object> params, ParameterizedTypeReference<T> bodyType);
    <T> T post(String path, Map<String, Object> params, ParameterizedTypeReference<T> bodyType);
    <T> T getPublic(String path, Map<String, Object> params, ParameterizedTypeReference<T> bodyType); // UNSIGNED

}
