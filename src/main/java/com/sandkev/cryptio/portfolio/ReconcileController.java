package com.sandkev.cryptio.portfolio;

// src/main/java/com/sandkev/cryptio/portfolio/ReconcileController.java
//package com.sandkev.cryptio.portfolio;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Comparator;

@Controller
@RequestMapping("/reconcile")
public class ReconcileController {

    private final ReconcileService reconcile;

    public ReconcileController(ReconcileService reconcile) {
        this.reconcile = reconcile;
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "primary") String account,
                       @RequestParam(defaultValue = "binance") String platform, // for now only binance
                       @RequestParam(defaultValue = "gbp") String vs,           // future pricing if needed
                       @RequestParam(required = false) BigDecimal minAbsDelta,  // hide tiny diffs (e.g., 0.00000010)
                       @RequestParam(defaultValue = "deltaDesc") String sort,   // deltaDesc|assetAsc
                       Model model) {

        var lines = switch (platform.toLowerCase()) {
            case "binance" -> reconcile.reconcileBinance(account);
            // case "kraken"  -> reconcile.reconcileKraken(account); // add later
            default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
        };

        // Filter by absolute delta threshold if provided
        if (minAbsDelta != null) {
            lines = lines.stream()
                    .filter(l -> l.delta().abs().compareTo(minAbsDelta) >= 0)
                    .toList();
        }

        // Sorting
        Comparator<ReconcileService.Line> cmp = switch (sort) {
            case "assetAsc" -> Comparator.comparing(ReconcileService.Line::asset, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing((ReconcileService.Line l) -> l.delta().abs()).reversed();
        };
        lines = lines.stream().sorted(cmp).toList();

        // Totals
        var totalSnapshot = lines.stream().map(ReconcileService.Line::fromSnapshot)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var totalTx = lines.stream().map(ReconcileService.Line::fromTx)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var totalAbsDelta = lines.stream().map(l -> l.delta().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        model.addAttribute("account", account);
        model.addAttribute("platform", platform);
        model.addAttribute("vs", vs);
        model.addAttribute("minAbsDelta", minAbsDelta);
        model.addAttribute("sort", sort);
        model.addAttribute("lines", lines);
        model.addAttribute("totalSnapshot", totalSnapshot);
        model.addAttribute("totalTx", totalTx);
        model.addAttribute("totalAbsDelta", totalAbsDelta);

        return "reconcile";
    }
}
