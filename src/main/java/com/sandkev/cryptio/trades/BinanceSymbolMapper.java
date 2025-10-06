// src/main/java/com/sandkev/cryptio/binance/BinanceSymbolMapper.java
package com.sandkev.cryptio.trades;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BinanceSymbolMapper {

    // Explicit overrides or rebrands
    private static final Map<String,String> OVERRIDES = Map.ofEntries(
            Map.entry("LUNC", "LUNCUSDT"),  // old LUNA
            Map.entry("LUNA", "LUNAUSDT"),
            Map.entry("POL",  "POLUSDT"),   // (MATIC rebrand)
            Map.entry("MATIC","MATICUSDT"),
            Map.entry("BUSD","BUSDUSDT"),   // may be delisted for trading; ingest may return 400 if not traded
            Map.entry("GBP", "GBPUSDT")     // fiat token, only if listed
            // add any other exceptions you hit
    );

    /** Return a Binance market symbol (e.g. BTCUSDT) or null if we should skip. */
    public String toMarket(String assetRaw) {
        if (assetRaw == null || assetRaw.isBlank()) return null;

        // strip suffixes like ".F"
        String a = assetRaw.trim().toUpperCase();
        int dot = a.indexOf('.');
        if (dot > 0) a = a.substring(0, dot);

        // explicit overrides first
        if (OVERRIDES.containsKey(a)) return OVERRIDES.get(a);

        // default heuristic: USDT quote
        return a + "USDT";
    }
}
