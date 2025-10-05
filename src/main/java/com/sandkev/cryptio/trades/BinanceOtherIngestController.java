package com.sandkev.cryptio.trades;

// src/main/java/com/sandkev/cryptio/binance/BinanceOtherIngestController.java
//package com.sandkev.cryptio.binance;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/binance")
public class BinanceOtherIngestController {

    private final BinanceOtherIngestService svc;

    public BinanceOtherIngestController(BinanceOtherIngestService svc) { this.svc = svc; }

    @PostMapping("/ingest-dust")
    public String dust(@RequestParam(defaultValue = "primary") String account,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        int n = svc.ingestDust(account, since);
        return "Dust conversions ingested: " + n;
    }

    @PostMapping("/ingest-convert")
    public String convert(@RequestParam(defaultValue = "primary") String account,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        int n = svc.ingestConvertTrades(account, since);
        return "Convert trades ingested: " + n;
    }

    @PostMapping("/ingest-rewards")
    public String rewards(@RequestParam(defaultValue = "primary") String account,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        int n = svc.ingestRewards(account, since);
        return "Rewards ingested: " + n;
    }
}

