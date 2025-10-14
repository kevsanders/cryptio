// src/main/java/com/sandkev/cryptio/kraken/KrakenSymbolMapper.java
package com.sandkev.cryptio.exchange.kraken;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class KrakenSymbolMapper {

    // Base asset overrides (Kraken uses XBT for BTC)
    private static final Map<String,String> BASE_OVERRIDES = Map.ofEntries(
            Map.entry("BTC", "XBT"),
            Map.entry("WETH", "ETH") // example alias; add more as needed
    );

    // Quote preference order for spot markets
    private static final List<String> PREFERRED_QUOTES = List.of("USDT", "USD", "EUR", "GBP");

    /** Normalize raw app asset to Kraken base code (e.g., "ADA.F" -> "ADA", "BTC" -> "XBT"). */
    public String toKrakenBase(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String a = raw.trim().toUpperCase();

        int dot = a.indexOf('.');
        if (dot > 0) a = a.substring(0, dot);   // strip suffix like ".F"

        return BASE_OVERRIDES.getOrDefault(a, a);
    }

    /**
     * Produce candidate pair altnames (e.g., "XBTUSDT", "XBTUSD", ...).
     * Kraken’s REST accepts "pair" as altname (e.g., XBTUSDT / ETHUSD).
     */
    public List<String> candidatePairs(String normalizedKrakenBase) {
        var out = new ArrayList<String>();
        if (normalizedKrakenBase == null || normalizedKrakenBase.isBlank()) return out;
        for (String q : PREFERRED_QUOTES) {
            out.add(normalizedKrakenBase + q);
        }
        return out;
    }
}
