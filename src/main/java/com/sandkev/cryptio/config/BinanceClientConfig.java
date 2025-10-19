package com.sandkev.cryptio.config;

import java.time.Duration;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.balance.BinanceSignedClientImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(BinanceClientConfig.BinanceClientProperties.class)
@RequiredArgsConstructor
public class BinanceClientConfig {

    private final BinanceClientProperties props;

    @Bean("binanceClient")
    @Qualifier("binanceClient")
    public WebClient binanceClient() {
        HttpClient http = HttpClient.create()
                .responseTimeout(Duration.ofMillis(props.timeoutMs()))
                .compress(true);
        return WebClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("X-MBX-APIKEY", props.apiKey())
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    @Bean
    @Qualifier("binanceSignedClient")
    public BinanceSignedClient binanceSignedClient(
            @Qualifier("binanceClient") WebClient binanceClient
    ) {
        return new BinanceSignedClientImpl(binanceClient, props);
    }

    @ConfigurationProperties("binance.client")
    public record BinanceClientProperties(
            String baseUrl,
            String apiKey,
            String secretKey,
            long   recvWindow,
            int    timeoutMs
    ) {}
}
