// src/main/java/com/sandkev/cryptio/web/ReconcileController.java
package com.sandkev.cryptio.web;

import com.sandkev.cryptio.portfolio.ReconcileService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Controller
//@RequestMapping("/v2")
public class ReconcileController {

    private final ReconcileService recon;

    public ReconcileController(ReconcileService recon) {
        this.recon = recon;
    }

    @GetMapping("/reconcile")
    public String reconcile(@RequestParam(defaultValue = "primary") String account,
                            @RequestParam(defaultValue = "binance") String platform,
                            @RequestParam(required = false) BigDecimal minAbsDelta,
                            @RequestParam(defaultValue = "deltaDesc") String sort,
                            Model model) {

        var lines = recon.lines(account, platform, minAbsDelta, sort); // list with asset(), fromSnapshot(), fromTx(), delta()
        var totals = recon.totals(lines);

        model.addAttribute("account", account);
        model.addAttribute("platform", platform);
        model.addAttribute("minAbsDelta", minAbsDelta);
        model.addAttribute("sort", sort);
        model.addAttribute("lines", lines);
        model.addAttribute("totalSnapshot", totals.snapshot());
        model.addAttribute("totalTx", totals.tx());
        model.addAttribute("totalAbsDelta", totals.absDelta());

        return "v2/reconcile";
    }
}
