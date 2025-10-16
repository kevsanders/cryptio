// src/main/java/com/sandkev/cryptio/exchange/binance/BinanceSymbolTradesIngest.java
package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.exchange.binance.ingest.IdCursorIngest;
import com.sandkev.cryptio.exchange.binance.ingest.RowResult;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxUpserter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public class BinanceSymbolTradesIngest extends IdCursorIngest<Map<String,Object>> {
    private final String symbol;
    private final long seedStartMs;
    public BinanceSymbolTradesIngest(BinanceSignedClient client, IngestCheckpointDao ckpt, TxUpserter tx, String symbol, long seedStartMs) {
        super(client, ckpt, tx);
        this.symbol = symbol;
        this.seedStartMs = seedStartMs;
    }
    @Override protected String kind()   { return "trades:"+symbol; }
    @Override protected String path()   { return "/api/v3/myTrades"; }
    @Override protected String symbol() { return symbol; }
    @Override protected long startTimeMs() { return seedStartMs; }

    @Override protected RowResult handleRow(Map<String,Object> t, String accountRef) {
        long id   = ((Number) t.get("id")).longValue();
        long time = ((Number) t.get("time")).longValue();

        String commissionAsset = (String) t.get("commissionAsset");
        BigDecimal qty         = new BigDecimal(String.valueOf(t.get("qty")));
        BigDecimal price       = new BigDecimal(String.valueOf(t.get("price")));
        BigDecimal commission  = new BigDecimal(String.valueOf(t.getOrDefault("commission","0")));
        boolean isBuyer        = Boolean.TRUE.equals(t.get("isBuyer"));

        String base  = symbol.substring(0, symbol.length() - quoteLen(symbol));
        String quote = symbol.substring(base.length());

        int inserted =0;
        inserted += tx.upsert("binance", accountRef, base, quote, isBuyer ? "BUY" : "SELL",
                qty, price, commission, commissionAsset, Instant.ofEpochMilli(time),
                "trade:"+symbol+":"+id);
        return RowResult.many(inserted, time);
    }
    @Override protected long extractId(Map<String,Object> r) {
        return ((Number) r.get("id")).longValue();
    }

    private static int quoteLen(String s) {
        // adapt your known-quote list; simple heuristic below
        String[] qs = {"USDT","FDUSD","BUSD","USDC","BTC","ETH","BNB","EUR","GBP","TRY","AUD","BRL","ARS","MXN","ZAR","PLN","RUB","UAH","IDR","NGN","SAR","AED","JPY","CAD","CHF","INR"};
        for (String q : qs) if (s.endsWith(q)) return q.length();
        return 3; // best-effort
    }
}
