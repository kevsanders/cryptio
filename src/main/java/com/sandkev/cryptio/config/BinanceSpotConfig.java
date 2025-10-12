package com.sandkev.cryptio.config;

import com.sandkev.cryptio.spot.BinanceSignedClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(BinanceSpotProperties.class)
@RequiredArgsConstructor
public class BinanceSpotConfig {
    private final BinanceSpotProperties props;

    @Bean("binanceClient")
    @Qualifier("binanceClient")
    public WebClient binanceClient() {
        var http = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(10));
        return WebClient.builder()
                .baseUrl(props.baseUrl()) // e.g. https://api.binance.com
                .defaultHeader("X-MBX-APIKEY", props.apiKey())
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    @Bean
    @Qualifier("binanceSignedClient")
    public BinanceSignedClient binanceSignedClient(
            @Qualifier("binanceClient") WebClient binanceClient
    ) {
        return new BinanceSignedClient(
                binanceClient, props
        );
    }

}
