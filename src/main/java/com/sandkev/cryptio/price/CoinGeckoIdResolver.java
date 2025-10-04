package com.sandkev.cryptio.price;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves symbols like BTC -> coin ids like "bitcoin".
 * Starts with a small known map and learns as you add assets.
 */
@Component
public class CoinGeckoIdResolver {

    // Seed common ids; extend as needed
    private static final Map<String, String> WELL_KNOWN = Map.ofEntries(
            Map.entry("BTC", "bitcoin"),
            Map.entry("ETH", "ethereum"),
            Map.entry("USDT", "tether"),
            Map.entry("USDC", "usd-coin"),
            Map.entry("BNB", "binancecoin"),
            Map.entry("XRP", "ripple"),
            Map.entry("DOGE", "dogecoin"),
            Map.entry("ACA", "acala"),
            Map.entry("ADA", "cardano"),
            Map.entry("ADA.F", "cardano"),     // .F variant, map to ADA
            Map.entry("AIR", "altair"),        // Altair (AIR) on Polkadot
            Map.entry("ALGO", "algorand"),
            Map.entry("ATOM", "cosmos"),
            Map.entry("ATOM.F", "cosmos"),
            Map.entry("AVAX", "avalanche-2"),
            Map.entry("BABY", "babylon"),        // assuming BABY = babylon
            Map.entry("BSX", "basilisk"),        // Basilisk (BSX, Kusama ecosystem)
            Map.entry("BT.F", "bitcoin"),        // map to Bitcoin
            Map.entry("DAI", "dai"),
            Map.entry("DOT", "polkadot"),
            Map.entry("DOT.F", "polkadot"),
            Map.entry("ETH.F", "ethereum"),
            Map.entry("GLMR", "moonbeam"),
            Map.entry("HDX", "hydradx"),
            Map.entry("KSM", "kusama"),
            Map.entry("KSM.F", "kusama"),
            Map.entry("LINK", "chainlink"),
            Map.entry("LTC", "litecoin"),
            Map.entry("LUNA", "terra-luna"),        // legacy Terra
            Map.entry("LUNA2", "terra-luna-2"),     // Terra 2.0
            Map.entry("MOVR", "moonriver"),
            Map.entry("POL", "polygon-ecosystem-token"), // POL (Polygon upgrade token)
            Map.entry("POL.F", "polygon-ecosystem-token"),
            Map.entry("REP", "augur"),
            Map.entry("RUNE", "thorchain"),
            Map.entry("SOL", "solana"),
            Map.entry("SOL.F", "solana"),
            Map.entry("SUI", "sui"),
            Map.entry("SUI.F", "sui"),
            Map.entry("SUPER", "superfarm"),       // SuperFarm
            Map.entry("THETA", "theta-token"),
            Map.entry("USD", "usd-coin"),          // treat as USD stablecoin
            Map.entry("WELL", "moonwell-artemis"), // Moonwell (WELL)
            Map.entry("XDG", "dogecoin"),          // XDG alias → Dogecoin
            Map.entry("XLM", "stellar")
    );

    private final Map<String, String> cache = new ConcurrentHashMap<>(WELL_KNOWN);

    public Optional<String> resolve(String symbol) {
        if (symbol == null) return Optional.empty();
        return Optional.ofNullable(cache.get(symbol.toUpperCase(Locale.ROOT)));
    }

    public void learn(String symbol, String coinId) {
        if (symbol != null && coinId != null) {
            cache.put(symbol.toUpperCase(Locale.ROOT), coinId);
        }
    }
}
