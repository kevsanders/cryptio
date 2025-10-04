package com.sandkev.cryptio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kraken.spot")
public record KrakenSpotProperties(
        String baseUrl,     // e.g. https://api.kraken.com
        String apiKey,
        String secretKey,   // base64 secret from Kraken
        int    timeoutMs
) {}