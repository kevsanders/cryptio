package com.sandkev.cryptio.tax;

import org.springframework.stereotype.Service;

@Service
public class CostBasisService {
    private final TaxLotRepo lots;

    public CostBasisService(TaxLotRepo lots) {
        this.lots = lots;
    }

/*
    @Transactional
    public void applyTrade(Asset asset, BigDecimal qty, BigDecimal price, boolean isBuy, Instant ts) {
        if (isBuy) {
            TaxLot taxLot = new TaxLot( asset.symbol(), qty, qty.multiply(price), ts);
            lots.save(taxLot);
            return;
        }

        // sell: deplete FIFO lots
        var remaining = qty;
        var fifo = lots.findAll().stream()
                .filter(l -> l.getAsset().equals(asset.symbol()) && l.getQtyOpen().signum() > 0)
                .sorted(Comparator.comparing(TaxLot::getOpenedAt))
                .iterator();
        while (remaining.signum() > 0 && fifo.hasNext()) {
            var lot = fifo.next();
            var take = remaining.min(lot.getQtyOpen());
            lot.setQtyOpen(lot.getQtyOpen().subtract(take));
            lots.save(lot);
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) throw new IllegalStateException("Short sell not supported");
    }
*/
}



