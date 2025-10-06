package com.sandkev.cryptio.trades;

// src/main/java/com/sandkev/cryptio/binance/BinanceIngestController.java
//package com.sandkev.cryptio.binance;

import com.sandkev.cryptio.portfolio.ReconcileService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/binance")
public class BinanceIngestController {

    private final BinanceTradeIngestService trades;
    private final ReconcileService reconcile;

    public BinanceIngestController(BinanceTradeIngestService trades, ReconcileService reconcile) {
        this.trades = trades;
        this.reconcile = reconcile;
    }

    @PostMapping("/ingest-trades")
    public String ingestTrades(@RequestParam(defaultValue = "primary") String account,
                               @RequestParam(required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        int n = trades.ingestAllAssets(account, since);
        return "Binance trades ingested: " + n;
    }

    @GetMapping("/reconcile")
    public Object reconcile(@RequestParam(defaultValue = "primary") String account) {
        return reconcile.reconcileBinance(account);
    }
}
