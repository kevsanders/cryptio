package com.sandkev.cryptio.portfolio;

import com.sandkev.cryptio.spot.BalanceIngestService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ValuationController {

    private final PortfolioValuationService valuation;
    private final BalanceIngestService ingest;

    public ValuationController(PortfolioValuationService valuation, BalanceIngestService ingest) {
        this.valuation = valuation;
        this.ingest = ingest;
    }

    @GetMapping("/value")
    public String value(@RequestParam(defaultValue = "primary") String account,
                        @RequestParam(defaultValue = "gbp") String vs,
                        Model model) {

        var res = valuation.value(account, vs);
        model.addAttribute("account", account);
        model.addAttribute("vs", res.vsCurrency());
        model.addAttribute("lines", res.lines());
        model.addAttribute("total", res.total());
        model.addAttribute("unresolved", res.unresolved());
        return "value";
    }

    @PostMapping("/value/refresh")
    public String refreshAndValue(@RequestParam(defaultValue = "primary") String account,
                                  @RequestParam(defaultValue = "gbp") String vs) {
        // Pull fresh balances then redirect to valuation page
        ingest.ingestBinance(account);
        ingest.ingestKraken(account);
        return "redirect:/value?account=" + account + "&vs=" + vs;
    }
}

