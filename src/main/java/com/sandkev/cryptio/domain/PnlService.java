package com.sandkev.cryptio.domain;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class PnlService {
    private final HoldingRepo holdings;

    public PnlService(HoldingRepo holdings) {
        this.holdings = holdings;
    }

    public record PnlLine(String asset, BigDecimal qty, BigDecimal mkt, BigDecimal cost, BigDecimal upnl) {}
    public List<PnlLine> compute(Map<String, BigDecimal> lastPrices) {
        return holdings.findAll().stream().map(h -> {
            var mkt = h.getQuantity().multiply(lastPrices.getOrDefault(h.getAsset(), BigDecimal.ZERO));
            var cost = h.getQuantity().multiply(h.getAvgCost());
            return new PnlLine(h.getAsset(), h.getQuantity(), mkt, cost, mkt.subtract(cost));
        }).toList();
    }
}