package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class BinanceSymbolRegistry {

    private static final ParameterizedTypeReference<List<Map<String,Object>>> TICKER_LIST =
            new ParameterizedTypeReference<>() {};
    private static final List<String> QUOTES = List.of("USDT", "FDUSD", "USDC", "BTC");

    private final BinanceSignedClient client;

    private volatile Set<String> spotSymbols = Set.of();

    @PostConstruct
    public void load() {
        // public endpoint, NO signing
        List<Map<String,Object>> tickers = client.getPublic(
                "/api/v3/ticker/price", Map.of(), TICKER_LIST);

        Set<String> symbols = tickers.stream()
                .map(m -> String.valueOf(m.get("symbol")))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        this.spotSymbols = symbols;
    }

    /** Exact pair like BTCUSDT */
    public boolean isValid(String symbol) {
        return spotSymbols.contains(symbol);
    }

    /** Resolve base to a tradable pair using a quote preference list */
    public Optional<String> resolve(String base) {
        String u = base.toUpperCase(Locale.ROOT);
        for (String q : QUOTES) {
            String sym = u + q;
            if (spotSymbols.contains(sym)) return Optional.of(sym);
        }
        return Optional.empty();
    }
}
