package com.sandkev.cryptio.portfolio;

import com.sandkev.cryptio.trades.BinanceOtherIngestService;
import com.sandkev.cryptio.trades.BinanceTradeIngestService;
import com.sandkev.cryptio.trades.BinanceTransferIngestService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DashboardIngestController {

    private final BinanceTradeIngestService trades;
    private final BinanceTransferIngestService transfers;
    private final BinanceOtherIngestService other;

    public DashboardIngestController(BinanceTradeIngestService trades,
                                     BinanceTransferIngestService transfers,
                                     BinanceOtherIngestService other) {
        this.trades = trades;
        this.transfers = transfers;
        this.other = other;
    }

    /** Runs all Binance ingests using checkpoints (no 'since' → resumes safely). */
    @PostMapping("/dashboard/ingest-all")
    public String ingestAll(@RequestParam(defaultValue = "primary") String account,
                            @RequestParam(defaultValue = "gbp") String vs) {
        // Pass null for 'since' to use each service's checkpoint logic.
        other.ingestAll(account, null);
        return "redirect:/dashboard?account=" + account + "&vs=" + vs;
    }

}
