package com.sandkev.cryptio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(BinanceSpotProperties.class)
public class BinanceSpotConfig {
    @Bean
    WebClient binanceWebClient(BinanceSpotProperties p) {
        var http = HttpClient.create().responseTimeout(Duration.ofMillis(p.timeoutMs())).compress(true);
        return WebClient.builder()
                .baseUrl(p.baseUrl())
                .defaultHeader("X-MBX-APIKEY", p.apiKey())
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }
}
