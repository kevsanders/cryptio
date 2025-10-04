package com.sandkev.cryptio.price;

import com.github.benmanes.caffeine.cache.*;
import com.sandkev.cryptio.config.CoinGeckoProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

@Service
public class CoinGeckoPriceService implements PriceService {

    private final WebClient http;
    private final Retry retry;
    private final Cache<String, Object> cache;
    private final Duration ttl;

    public CoinGeckoPriceService(WebClient coingeckoWebClient, Retry geckoRetry, CoinGeckoProperties props) {
        this.http = coingeckoWebClient;
        this.retry = geckoRetry;
        this.ttl = Duration.parse(props.cacheTtl());
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(10_000)
                .build();
    }

    @Override
    public Map<String, BigDecimal> getSimplePrice(Set<String> coinIds, String vsCurrency) {
        var res = getSimplePrice(coinIds, Set.of(vsCurrency.toLowerCase(Locale.ROOT)));
        // Flatten to single vs
        var out = new LinkedHashMap<String, BigDecimal>();
        for (var id : coinIds) {
            var vsMap = res.getOrDefault(id, Map.of());
            var val = vsMap.get(vsCurrency.toLowerCase(Locale.ROOT));
            if (val != null) out.put(id, val);
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, BigDecimal>> getSimplePrice(Set<String> coinIds, Set<String> vsCurrencies) {
        if (coinIds.isEmpty() || vsCurrencies.isEmpty()) return Map.of();
        var key = "simple:" + String.join(",", sorted(coinIds)) + "|" + String.join(",", sortedLower(vsCurrencies));
        return (Map<String, Map<String, BigDecimal>>) cache.get(key, k -> fetchSimplePrice(coinIds, vsCurrencies));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, BigDecimal>> getTokenPrice(String chainId, Set<String> contractAddresses, Set<String> vsCurrencies) {
        if (contractAddresses.isEmpty() || vsCurrencies.isEmpty()) return Map.of();
        var key = "token:" + chainId + ":" + String.join(",", sortedLower(contractAddresses)) + "|" + String.join(",", sortedLower(vsCurrencies));
        return (Map<String, Map<String, BigDecimal>>) cache.get(key, k -> fetchTokenPrice(chainId, contractAddresses, vsCurrencies));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getSupportedVsCurrencies() {
        var key = "supported_vs";
        return (List<String>) cache.get(key, k -> fetchSupportedVs());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<CoinInfo> getCoinsList() {
        var key = "coins_list";
        return (List<CoinInfo>) cache.get(key, k -> fetchCoinsList());
    }

    // ---- HTTP calls ----

    private Map<String, Map<String, BigDecimal>> fetchSimplePrice(Set<String> ids, Set<String> vs) {
        String idsParam = String.join(",", sorted(ids));
        String vsParam  = String.join(",", sortedLower(vs));

        return http.get()
                .uri(uri -> uri.path("/simple/price")
                        .queryParam("ids", idsParam)
                        .queryParam("vs_currencies", vsParam)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(s -> s.value() == 429, r -> Mono.error(new RuntimeException("Rate limited by CoinGecko (429)")))
                .bodyToMono(Map.class)
                .retryWhen(retry)
                .map(this::toNestedBigDecimalMap)
                .block();
    }

    private Map<String, Map<String, BigDecimal>> fetchTokenPrice(String chainId, Set<String> contracts, Set<String> vs) {
        String contractsParam = String.join(",", sortedLower(contracts));
        String vsParam        = String.join(",", sortedLower(vs));

        return http.get()
                .uri(uri -> uri.path("/simple/token_price/{chain}")
                        .queryParam("contract_addresses", contractsParam)
                        .queryParam("vs_currencies", vsParam)
                        .build(chainId))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(s -> s.value() == 429, r -> Mono.error(new RuntimeException("Rate limited by CoinGecko (429)")))
                .bodyToMono(Map.class)
                .retryWhen(retry)
                .map(this::toNestedBigDecimalMap)
                .block();
    }

    private List<String> fetchSupportedVs() {
        return http.get()
                .uri("/simple/supported_vs_currencies")
                .retrieve()
                .bodyToMono(List.class)
                .retryWhen(retry)
                .block();
    }

    private List<CoinInfo> fetchCoinsList() {
        return http.get()
                .uri(uri -> uri.path("/coins/list")
                        .queryParam("include_platform", false)
                        .build())
                .retrieve()
                .bodyToFlux(Map.class)
                .map(m -> new CoinInfo(
                        String.valueOf(m.get("id")),
                        String.valueOf(m.get("symbol")),
                        String.valueOf(m.get("name"))))
                .collectList()
                .retryWhen(retry)
                .block();
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, BigDecimal>> toNestedBigDecimalMap(Map<?, ?> in) {
        var out = new LinkedHashMap<String, Map<String, BigDecimal>>();
        in.forEach((k, v) -> {
            var vsRaw = (Map<String, Object>) v;
            var inner = new LinkedHashMap<String, BigDecimal>();
            vsRaw.forEach((vk, vv) -> inner.put(vk.toLowerCase(Locale.ROOT), new BigDecimal(String.valueOf(vv))));
            out.put(String.valueOf(k), inner);
        });
        return out;
    }

    private static List<String> sorted(Collection<String> c) {
        return c.stream().sorted().toList();
    }

    private static List<String> sortedLower(Collection<String> c) {
        return c.stream().map(s -> s.toLowerCase(Locale.ROOT)).sorted().toList();
    }
}

