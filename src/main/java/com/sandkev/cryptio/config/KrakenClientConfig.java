package com.sandkev.cryptio.config;

import com.sandkev.cryptio.exchange.kraken.KrakenSignedClient;
import com.sandkev.cryptio.exchange.kraken.KrakenSignedClientImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(KrakenClientConfig.KrakenClientProperties.class)
@RequiredArgsConstructor
public class KrakenClientConfig {

    private final KrakenClientProperties props;

    @Bean("krakenWebClient")
    @Qualifier("krakenWebClient")
    public WebClient krakenWebClient() {
        HttpClient http = HttpClient.create()
                .responseTimeout(Duration.ofMillis(props.timeoutMs()))
                .compress(true);
        return WebClient.builder()
                .baseUrl(props.baseUrl())
                //.defaultHeader("X-MBX-APIKEY", props.apiKey())
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    @Bean
    @Qualifier("krakenSignedClient")
    public KrakenSignedClient krakenSignedClient(
            @Qualifier("krakenWebClient") WebClient krakenWebClient
    ) {
        return new KrakenSignedClientImpl(krakenWebClient, props);
    }

    @ConfigurationProperties("kraken.client")
    public record KrakenClientProperties(
            String baseUrl,     // e.g. https://api.kraken.com
            String apiKey,
            String secretKey,   // base64 secret from Kraken
            int    timeoutMs
    ) {}

}