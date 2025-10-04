package com.sandkev.cryptio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("binance.spot")
public record BinanceSpotProperties(
        String baseUrl,     // e.g. https://api.binance.com
        String apiKey,
        String secretKey,
        long   recvWindow,  // e.g. 5000
        int    timeoutMs    // e.g. 5000
) {}
