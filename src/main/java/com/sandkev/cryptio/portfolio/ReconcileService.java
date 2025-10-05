package com.sandkev.cryptio.portfolio;

// src/main/java/com/sandkev/cryptio/portfolio/ReconcileService.java
//package com.sandkev.cryptio.portfolio;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ReconcileService {
    private final JdbcTemplate jdbc;
    public ReconcileService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Line(String asset, BigDecimal fromTx, BigDecimal fromSnapshot, BigDecimal delta) {}

    public List<Line> reconcileBinance(String accountRef) {

        // 1) Net quantity movements per asset with all types handled
        var qtyRows = jdbc.query("""
            select base as asset,
                   sum(case
                         when type in ('BUY','DEPOSIT','CONVERT_IN','REWARD') then quantity
                         when type in ('SELL','WITHDRAW','CONVERT_OUT')       then -quantity
                         else 0
                       end) as qty
            from tx
            where exchange='binance' and account_ref=?
            group by base
        """, (rs,i) -> new Object[]{ rs.getString(1), rs.getBigDecimal(2) }, accountRef);

        // 2) Total fees paid in each asset (trade fees, withdraw fees, dust fees in BNB)
        var feeRows = jdbc.query("""
            select fee_asset as asset, sum(coalesce(fee,0)) as fee_total
            from tx
            where exchange='binance' and account_ref=? and fee is not null and fee_asset is not null
            group by fee_asset
        """, (rs,i) -> new Object[]{ rs.getString(1), rs.getBigDecimal(2) }, accountRef);

        // 3) Latest snapshot from exchange (ground truth)
        var snapRows = jdbc.query("""
            select asset, sum(total_amt) as qty
            from v_latest_balance
            where exchange='binance' and account=?
            group by asset
        """, (rs,i) -> new Object[]{ rs.getString(1), rs.getBigDecimal(2) }, accountRef);

        var qtyMap = new java.util.HashMap<String, BigDecimal>();
        for (var r : qtyRows) qtyMap.put((String) r[0], (BigDecimal) r[1]);

        var feeMap = new java.util.HashMap<String, BigDecimal>();
        for (var r : feeRows) feeMap.put((String) r[0], (BigDecimal) r[1]);

        var snapMap = new java.util.HashMap<String, BigDecimal>();
        for (var r : snapRows) snapMap.put((String) r[0], (BigDecimal) r[1]);

        var assets = new java.util.TreeSet<String>();
        assets.addAll(qtyMap.keySet());
        assets.addAll(feeMap.keySet());
        assets.addAll(snapMap.keySet());

        var out = new java.util.ArrayList<Line>();
        for (String a : assets) {
            BigDecimal netQty = qtyMap.getOrDefault(a, BigDecimal.ZERO);
            BigDecimal fees   = feeMap.getOrDefault(a, BigDecimal.ZERO);
            BigDecimal fromTx = netQty.subtract(fees); // deduct all fees in this asset
            BigDecimal fromSn = snapMap.getOrDefault(a, BigDecimal.ZERO);
            out.add(new Line(a, fromTx, fromSn, fromSn.subtract(fromTx)));
        }
        return out;
    }
}

