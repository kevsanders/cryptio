package com.sandkev.cryptio.balance;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class SymbolResolver {

    private static final List<String> QUOTES = List.of("USDT", "FDUSD", "USDC", "BTC");

    /** Returns first available symbol like SUPERUSDT, DOTUSDT, etc., or empty if none. */
    public static Optional<String> resolveTradingSymbol(String asset, Set<String> exchangeSymbols) {
        for (String q : QUOTES) {
            String sym = asset + q;
            if (exchangeSymbols.contains(sym)) return Optional.of(sym);
        }
        return Optional.empty();
    }
}
