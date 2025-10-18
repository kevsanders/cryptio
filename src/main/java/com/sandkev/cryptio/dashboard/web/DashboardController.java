package com.sandkev.cryptio.dashboard.web;

import com.sandkev.cryptio.exchange.binance.BinanceCompositeIngestService;
import com.sandkev.cryptio.portfolio.PortfolioValuationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Controller
public class DashboardController {

    public static final String DASHBOARD = "dashboard";
    private final PortfolioValuationService valuation;
    private final BinanceCompositeIngestService binance;

    public DashboardController(PortfolioValuationService valuation,
                               BinanceCompositeIngestService binance) {
        this.valuation = valuation;
        this.binance = binance;
    }

    @GetMapping("/")
    public String rootRedirect() { return "redirect:/" + DASHBOARD; }

    @GetMapping("/" + DASHBOARD)
    public String dashboard(@RequestParam(defaultValue = "primary") String account,
                            @RequestParam(defaultValue = "gbp") String vs,
                            Model model) {

        var totals = valuation.platformTotals(account, vs);
        BigDecimal grand = totals.stream().map(t -> t.value()).reduce(BigDecimal.ZERO, BigDecimal::add);

        var top = valuation.topTokens(account, vs, 12, 0.01);
        var pie = valuation.pieData(account, vs, 0.02);

        model.addAttribute("account", account);
        model.addAttribute("vs", vs);
        model.addAttribute("platformTotals", totals);
        model.addAttribute("grandTotal", grand);
        model.addAttribute("topTokens", top);
        model.addAttribute("pieLabels", pie.labels());
        model.addAttribute("pieValues", pie.values());

        return DASHBOARD;
    }

    // ----- Actions wired to the composite façade -----

    @PostMapping("/"+DASHBOARD+"/ingest-all")
    public String ingestAll(@RequestParam(defaultValue = "primary") String account,
                            @RequestParam(defaultValue = "gbp") String vs,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
                            RedirectAttributes flash) {
        var res = binance.ingestAll(account, since);
        flash.addFlashAttribute("ingestMsg",
                "Binance ingest: " + res.total() + " rows "
                        + "(trades=" + res.trades() + ", deposits=" + res.deposits()
                        + ", withdrawals=" + res.withdrawals() + ", converts=" + res.converts()
                        + ", dust=" + res.dust() + ", rewards=" + res.rewards() + ")");
        return "redirect:/"+DASHBOARD+"?account=" + account + "&vs=" + vs;
    }

    @PostMapping("/binance/ingest-trades")
    public String ingestTrades(@RequestParam(defaultValue = "primary") String account,
                               @RequestParam(defaultValue = "gbp") String vs,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        binance.ingestTrades(account, since);
        return "redirect:/"+DASHBOARD+"?account=" + account + "&vs=" + vs;
    }

    @PostMapping("/binance/ingest-deposits")
    public String ingestDeposits(@RequestParam(defaultValue = "primary") String account,
                                 @RequestParam(defaultValue = "gbp") String vs,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        binance.ingestDeposits(account, since);
        return "redirect:/"+DASHBOARD+"?account=" + account + "&vs=" + vs;
    }

    @PostMapping("/binance/ingest-withdrawals")
    public String ingestWithdrawals(@RequestParam(defaultValue = "primary") String account,
                                    @RequestParam(defaultValue = "gbp") String vs,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        binance.ingestWithdrawals(account, since);
        return "redirect:/"+DASHBOARD+"?account=" + account + "&vs=" + vs;
    }

    @PostMapping("/binance/ingest-dust")
    public String ingestDust(@RequestParam(defaultValue = "primary") String account,
                             @RequestParam(defaultValue = "gbp") String vs,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        binance.ingestDust(account, since);
        return "redirect:/" + DASHBOARD + "?account=" + account + "&vs=" + vs;
    }

    @PostMapping("/binance/ingest-convert")
    public String ingestConvert(@RequestParam(defaultValue = "primary") String account,
                                @RequestParam(defaultValue = "gbp") String vs,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        binance.ingestConverts(account, since);
        return "redirect:/" + DASHBOARD + "?account=" + account + "&vs=" + vs;
    }

    @PostMapping("/binance/ingest-rewards")
    public String ingestRewards(@RequestParam(defaultValue = "primary") String account,
                                @RequestParam(defaultValue = "gbp") String vs,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        binance.ingestRewards(account, since);
        return "redirect:/" + DASHBOARD+ "?account=" + account + "&vs=" + vs;
    }

    // DashboardController.java
    @PostMapping("/binance/ingest-trades-from-list")
    public String ingestTradesFromList(@RequestParam("account") String accountRef,
                                       @RequestParam(value = "since", required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
                                       @RequestParam(value = "vs", required = false) String vs,
                                       RedirectAttributes redirectAttributes) {

        int upserts = binance.ingestTradesFromList(accountRef, since);

        redirectAttributes.addFlashAttribute("notice",
                "Binance trades (from list): upserted " + upserts + " rows");

        // preserve current dashboard context
        String query = "?exchange=binance&account=" + UriUtils.encode(accountRef, StandardCharsets.UTF_8)
                + (vs != null ? "&vs=" + UriUtils.encode(vs, StandardCharsets.UTF_8) : "");
        return "redirect:/" + DASHBOARD + query;
    }

}
