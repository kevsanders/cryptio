package com.sandkev.cryptio.portfolio;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReconcileService {

    private final JdbcTemplate jdbc;
    public ReconcileService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Row for the UI: Snapshot vs Tx-derived and the Delta = Snapshot − Tx */
    public record Line(String asset, BigDecimal fromSnapshot, BigDecimal fromTx, BigDecimal delta) {}

    /** Aggregates shown above the table. */
    public record Totals(BigDecimal snapshot, BigDecimal tx, BigDecimal absDelta) {}

    /**
     * Build reconciliation lines for a given platform/exchange and account.
     * @param accountRef   e.g. "primary"
     * @param platform     e.g. "binance" or "kraken" (case-insensitive)
     * @param minAbsDelta  optional filter (hide rows with |Δ| < threshold)
     * @param sort         "deltaDesc" (default) or "assetAsc"
     */
    public List<Line> lines(String accountRef, String platform,
                            BigDecimal minAbsDelta, String sort) {

        // --- 1) Net quantity movements per asset (buys/deposits/rewards/convert_in add, sells/withdraw/convert_out subtract)
        var qtyRows = jdbc.query("""
            select base as asset,
                   sum(case
                         when type in ('BUY','DEPOSIT','CONVERT_IN','REWARD') then quantity
                         when type in ('SELL','WITHDRAW','CONVERT_OUT')       then -quantity
                         else 0
                       end) as qty
            from tx
            where lower(exchange)=lower(?) and account_ref=?
            group by base
        """, (rs,i) -> new Object[]{ rs.getString(1), rs.getBigDecimal(2) }, platform, accountRef);

        // --- 2) Total fees paid in each asset
        var feeRows = jdbc.query("""
            select fee_asset as asset, sum(coalesce(fee,0)) as fee_total
            from tx
            where lower(exchange)=lower(?) and account_ref=? and fee is not null and fee_asset is not null
            group by fee_asset
        """, (rs,i) -> new Object[]{ rs.getString(1), rs.getBigDecimal(2) }, platform, accountRef);

        // --- 3) Ground truth snapshot (latest balance)
        var snapRows = jdbc.query("""
            select asset, sum(total_amt) as qty
            from v_latest_balance
            where lower(exchange)=lower(?) and account=?
            group by asset
        """, (rs,i) -> new Object[]{ rs.getString(1), rs.getBigDecimal(2) }, platform, accountRef);

        // Build maps
        var qtyMap  = toMap(qtyRows);
        var feeMap  = toMap(feeRows);
        var snapMap = toMap(snapRows);

        // Union of all assets seen anywhere
        var assets = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        assets.addAll(qtyMap.keySet());
        assets.addAll(feeMap.keySet());
        assets.addAll(snapMap.keySet());

        // Compose lines
        List<Line> lines = new ArrayList<>(assets.size());
        for (String a : assets) {
            BigDecimal netQty  = nz(qtyMap.get(a));
            BigDecimal fees    = nz(feeMap.get(a));
            BigDecimal fromTx  = netQty.subtract(fees);               // deduct fees paid in this asset
            BigDecimal snapQty = nz(snapMap.get(a));
            BigDecimal delta   = snapQty.subtract(fromTx);            // Δ = Snapshot − Tx-derived
            lines.add(new Line(a, snapQty, fromTx, delta));
        }

        // Optional filter by |Δ|
        if (minAbsDelta != null && minAbsDelta.signum() > 0) {
            BigDecimal threshold = minAbsDelta.abs();
            lines = lines.stream()
                    .filter(l -> l.delta().abs().compareTo(threshold) >= 0)
                    .collect(Collectors.toList());
        }

        // Sort
        if ("assetAsc".equalsIgnoreCase(sort)) {
            lines.sort(Comparator.comparing(Line::asset, String.CASE_INSENSITIVE_ORDER));
        } else {
            // default: |Δ| desc, tie-break by asset
            lines.sort(Comparator.<Line, BigDecimal>comparing(l -> l.delta().abs()).reversed()
                    .thenComparing(Line::asset, String.CASE_INSENSITIVE_ORDER));
        }

        return lines;
    }

    /** Compute totals for header pills. */
    public Totals totals(List<Line> lines) {
        BigDecimal snap = BigDecimal.ZERO;
        BigDecimal tx   = BigDecimal.ZERO;
        BigDecimal abs  = BigDecimal.ZERO;
        for (Line l : lines) {
            snap = snap.add(nz(l.fromSnapshot()));
            tx   = tx.add(nz(l.fromTx()));
            abs  = abs.add(nz(l.delta()).abs());
        }
        return new Totals(snap, tx, abs);
    }

    // --- helpers ---
    private static BigDecimal nz(BigDecimal x) {
        return x == null ? BigDecimal.ZERO : x;
    }
    private static Map<String, BigDecimal> toMap(List<Object[]> rows) {
        var m = new HashMap<String, BigDecimal>(rows.size() * 2);
        for (var r : rows) {
            String a = (String) r[0];
            BigDecimal v = (BigDecimal) r[1];
            if (a != null) m.put(a, v == null ? BigDecimal.ZERO : v);
        }
        return m;
    }
}
