// src/main/java/com/sandkev/cryptio/web/DashboardController.java
package com.sandkev.cryptio.dashboard.web;

import com.sandkev.cryptio.portfolio.PortfolioValuationService;
import com.sandkev.cryptio.exchange.binance.BinanceOtherIngestService;
import com.sandkev.cryptio.exchange.binance.BinanceTradeIngestService;
import com.sandkev.cryptio.exchange.binance.BinanceTransferIngestService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.Instant;

@Controller
public class DashboardController {

    private final PortfolioValuationService valuation;
    private final BinanceOtherIngestService binanceOther;
    private final BinanceTradeIngestService binanceTrades;
    private final BinanceTransferIngestService binanceTransfers;

    public DashboardController(PortfolioValuationService valuation,
                               BinanceOtherIngestService binanceOther,
                               BinanceTradeIngestService binanceTrades,
                               BinanceTransferIngestService binanceTransfers) {
        this.valuation = valuation;
        this.binanceOther = binanceOther;
        this.binanceTrades = binanceTrades;
        this.binanceTransfers = binanceTransfers;
    }

    @GetMapping("/")
    public String rootRedirect() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(defaultValue = "primary") String account,
                            @RequestParam(defaultValue = "gbp") String vs,
                            Model model) {

        var totals = valuation.platformTotals(account, vs); // List<PlatformTotal{platform,value}>
        BigDecimal grand = totals.stream().map(t -> t.value()).reduce(BigDecimal.ZERO, BigDecimal::add);

        var top = valuation.topTokens(account, vs, /*limit*/12, /*minPct*/0.01); // List<TokenSlice{asset,value}
        var pie = valuation.pieData(account, vs, /*minPct*/0.02); // returns (labels, values)

        model.addAttribute("account", account);
        model.addAttribute("vs", vs);
        model.addAttribute("platformTotals", totals);
        model.addAttribute("grandTotal", grand);
        model.addAttribute("topTokens", top);
        model.addAttribute("pieLabels", pie.labels());   // List<String>
        model.addAttribute("pieValues", pie.values());   // List<BigDecimal or Number>

        return "dashboard";
    }

    // Ingest buttons (reuse existing endpoints)
    @PostMapping("/dashboard/ingest-all")
    public String ingestAll(@RequestParam(defaultValue = "primary") String account,
                            @RequestParam(defaultValue = "gbp") String vs) {
        // You already have per-kind ingesters; this calls our façade
        binanceTrades.ingestAllAssets(account, null);
        binanceOther.ingestAll(account, null);
        binanceTransfers.ingestDeposits(account, null);
        binanceTransfers.ingestWithdrawals(account, null);
        return "redirect:/dashboard?account=" + account + "&vs=" + vs;
    }

    @PostMapping("/binance/ingest-trades")
    public String ingestTrades(@RequestParam(defaultValue = "primary") String account,
                               @RequestParam(defaultValue = "gbp") String vs) {
        binanceTrades.ingestAllAssets(account, null); // or call trade service directly
        return "redirect:/dashboard?account=" + account + "&vs=" + vs;
    }

    @PostMapping("/binance/ingest-deposits")
    public String ingestDeposits(@RequestParam(defaultValue = "primary") String account,
                                 @RequestParam(defaultValue = "gbp") String vs,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        // call your deposit service
        binanceTransfers.ingestDeposits(account, since);
        return "redirect:/dashboard?account=" + account + "&vs=" + vs;
    }

    @PostMapping("/binance/ingest-withdrawals")
    public String ingestWithdrawals(@RequestParam(defaultValue = "primary") String account,
                                 @RequestParam(defaultValue = "gbp") String vs,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        // call your deposit service
        binanceTransfers.ingestWithdrawals(account, since);
        return "redirect:/dashboard?account=" + account + "&vs=" + vs;
    }



    // ...repeat for withdrawals/dust/convert/rewards similarly, each redirecting back
    @PostMapping("/binance/ingest-dust")
    public String dust(@RequestParam(defaultValue = "primary") String account,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        binanceOther.ingestDust(account, since);
        return "redirect:/dashboard?account=" + account + "&vs=gbp";
    }

    @PostMapping("/binance/ingest-convert")
    public String convert(@RequestParam(defaultValue = "primary") String account,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        binanceOther.ingestConvertTrades(account, since);
        return "redirect:/dashboard?account=" + account + "&vs=gbp";
    }

    @PostMapping("/binance/ingest-rewards")
    public String rewards(@RequestParam(defaultValue = "primary") String account,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        binanceOther.ingestRewards(account, since);
        return "redirect:/dashboard?account=" + account + "&vs=gbp";
    }


}
