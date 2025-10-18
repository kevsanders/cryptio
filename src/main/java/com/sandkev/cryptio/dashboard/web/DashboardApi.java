// src/main/java/com/sandkev/cryptio/dashboard/web/DashboardApi.java
package com.sandkev.cryptio.dashboard.web;

import com.sandkev.cryptio.portfolio.PortfolioValuationService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardApi {

    private final PortfolioValuationService valuation;

    public DashboardApi(PortfolioValuationService valuation) {
        this.valuation = valuation;
    }

    @GetMapping("/dashboard")
    public DashboardResponse get(
            @RequestParam(defaultValue = "primary") String account,
            @RequestParam(defaultValue = "gbp") String vs
    ) {
        var totals = valuation.platformTotals(account, vs);
        BigDecimal grand = totals.stream().map(t -> t.value()).reduce(BigDecimal.ZERO, BigDecimal::add);
        var top = valuation.topTokens(account, vs, 12, 0.01);
        var pie = valuation.pieData(account, vs, 0.02);

        return new DashboardResponse(
                totals.stream().map(t -> new PlatformTotal(t.platform(), t.value())).toList(),
                grand,
                top.stream().map(tt -> new TopToken(tt.asset(), tt.value())).toList(),
                new PieData(pie.labels(), pie.values())
        );
    }

    public record DashboardResponse(
            List<PlatformTotal> platformTotals,
            BigDecimal grandTotal,
            List<TopToken> topTokens,
            PieData pie
    ) {}

    public record PlatformTotal(String platform, BigDecimal value) {}
    public record TopToken(String asset, BigDecimal value) {}
    public record PieData(List<String> labels, List<BigDecimal> values) {}
}
