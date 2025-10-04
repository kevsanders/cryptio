package com.sandkev.cryptio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.*;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(CoinGeckoProperties.class)
public class CoinGeckoConfig {

    @Bean
    WebClient coingeckoWebClient(CoinGeckoProperties p) {
        var http = HttpClient.create()
                .responseTimeout(Duration.ofMillis(p.timeoutMs()))
                .compress(true);

        var builder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .baseUrl(p.baseUrl())
                .defaultHeader("User-Agent", p.userAgent());

        if (p.apiKey() != null && !p.apiKey().isBlank()) {
            builder.defaultHeader(p.apiKeyHeader(), p.apiKey());
        }
        return builder.build();
    }

    @Bean
    Retry geckoRetry(CoinGeckoProperties p) {
        // 429/5xx backoff with jitter
        return Retry.backoff(p.maxRetries(), Duration.ofMillis(p.backoffMs()))
                .filter(th -> th instanceof WebClientResponseException ex
                        && (ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429))
                .transientErrors(true)
                .jitter(0.25);
    }
}
