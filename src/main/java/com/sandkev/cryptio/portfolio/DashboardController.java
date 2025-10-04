package com.sandkev.cryptio.portfolio;

import com.sandkev.cryptio.spot.BalanceIngestService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class DashboardController {

    private final PortfolioValuationService valuation;
    private final BalanceIngestService ingest;

    public DashboardController(PortfolioValuationService valuation, BalanceIngestService ingest) {
        this.valuation = valuation;
        this.ingest = ingest;
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(defaultValue = "primary") String account,
                            @RequestParam(defaultValue = "gbp") String vs,
                            Model model) {
        var d = valuation.valueDashboard(account, vs);
        model.addAttribute("account", account);
        model.addAttribute("vs", d.vsCurrency());
        model.addAttribute("platformTotals", d.platformTotals());
        model.addAttribute("pie", d.pie());
        model.addAttribute("total", d.totalValue());
        model.addAttribute("unresolved", d.unresolvedSymbols());

        // ADD THESE TWO LINES:
        model.addAttribute("pieLabels", d.pie().stream().map(PortfolioValuationService.PieSlice::asset).toList());
        model.addAttribute("pieValues", d.pie().stream().map(PortfolioValuationService.PieSlice::value).toList());

        return "dashboard";
    }

    @PostMapping("/dashboard/refresh")
    public String refresh(@RequestParam(defaultValue = "primary") String account,
                          @RequestParam(defaultValue = "gbp") String vs) {
        ingest.ingestBinance(account);
        ingest.ingestKraken(account);
        return "redirect:/dashboard?account=" + account + "&vs=" + vs;
    }
}

