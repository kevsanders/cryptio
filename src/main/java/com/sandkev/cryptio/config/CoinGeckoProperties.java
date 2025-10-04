package com.sandkev.cryptio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coingecko")
public record CoinGeckoProperties(
        String baseUrl,
        String apiKey,
        String apiKeyHeader,
        String userAgent,
        int timeoutMs,
        String cacheTtl,
        int maxRetries,
        int backoffMs
) {}
