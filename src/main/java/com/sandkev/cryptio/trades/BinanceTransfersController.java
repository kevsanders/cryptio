package com.sandkev.cryptio.trades;

// src/main/java/com/sandkev/cryptio/binance/BinanceTransfersController.java
//package com.sandkev.cryptio.binance;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/binance")
public class BinanceTransfersController {

    private final BinanceTransferIngestService svc;

    public BinanceTransfersController(BinanceTransferIngestService svc) { this.svc = svc; }

    @PostMapping("/ingest-deposits")
    public String deposits(@RequestParam(defaultValue = "primary") String account,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        int n = svc.ingestDeposits(account, since);
        return "Deposits ingested: " + n;
    }

    @PostMapping("/ingest-withdrawals")
    public String withdrawals(@RequestParam(defaultValue = "primary") String account,
                              @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        int n = svc.ingestWithdrawals(account, since);
        return "Withdrawals ingested: " + n;
    }
}
