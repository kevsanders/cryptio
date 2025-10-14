package com.sandkev.cryptio.exchange.kraken;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
class KrakenController {
    private final KrakenSpotPositionsService svc;
    KrakenController(KrakenSpotPositionsService svc) { this.svc = svc; }

    @GetMapping("/kraken/spot/balances")
    Map<String, BigDecimal> balances() { return svc.fetchSpotBalances(); }
}

