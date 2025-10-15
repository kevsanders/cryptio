package com.sandkev.cryptio.exchange.binance;

public interface BinanceSymbolMapper {
    /**
     * Current mapper: often maps base -> base+USDT (kept to avoid breaking changes).
     */
    String toMarket(String base);
    // Future: prefer methods like:
    // List<String> symbolsForBase(String base);
    // SymbolMeta metaFor(String symbol);
}
