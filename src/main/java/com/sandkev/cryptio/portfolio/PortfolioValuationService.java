package com.sandkev.cryptio.portfolio;

// src/main/java/com/sandkev/cryptio/portfolio/PortfolioValuationService.java

import com.sandkev.cryptio.price.CoinGeckoIdResolver;
import com.sandkev.cryptio.price.PriceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PortfolioValuationService {

    private static final MathContext MC = new MathContext(34, java.math.RoundingMode.HALF_UP);

    private final JdbcTemplate jdbc;
    private final PriceService prices;
    private final CoinGeckoIdResolver resolver;

    public PortfolioValuationService(JdbcTemplate jdbc, PriceService prices, CoinGeckoIdResolver resolver) {
        this.jdbc = jdbc;
        this.prices = prices;
        this.resolver = resolver;
    }

    /** Returns latest balances aggregated by asset across all exchanges for the given account. */
    public List<Holding> latestHoldings(String account) {
        // Aggregate by asset across exchanges (sum total_amt)
        var rows = jdbc.query("""
                select asset, sum(total_amt) as qty
                from v_latest_balance
                where account = ?
                group by asset
                order by asset
                """,
                (ResultSet rs, int i) -> new Holding(
                        rs.getString("asset"),
                        rs.getBigDecimal("qty")
                ),
                account
        );
        return rows;
    }

    public ValuationResult value(String account, String vsCurrency) {
        vsCurrency = vsCurrency.toLowerCase(Locale.ROOT);
        var holdings = latestHoldings(account);

        // Resolve coin ids
        Map<String, String> symbolToId = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (var h : holdings) {
            resolver.resolve(h.asset()).ifPresentOrElse(
                    id -> symbolToId.put(h.asset(), id),
                    () -> unresolved.add(h.asset())
            );
        }

        // Fetch prices
        Map<String, BigDecimal> idToPrice = symbolToId.isEmpty()
                ? Map.of()
                : prices.getSimplePrice(new LinkedHashSet<>(symbolToId.values()), vsCurrency);

        // Compute lines
        List<Line> lines = new ArrayList<>(holdings.size());
        BigDecimal totalValue = BigDecimal.ZERO;
        for (var h : holdings) {
            String coinId = symbolToId.get(h.asset());
            BigDecimal price = coinId != null ? idToPrice.get(coinId) : null;

            BigDecimal value = (price == null) ? null : h.qty().multiply(price, MC);
            if (value != null) totalValue = totalValue.add(value, MC);

            lines.add(new Line(h.asset(), h.qty(), price, value, coinId));
        }

        return new ValuationResult(vsCurrency, lines, totalValue, unresolved);
    }

    // DTOs
    public record Holding(String asset, BigDecimal qty) {}
    public record Line(String asset, BigDecimal qty, BigDecimal price, BigDecimal value, String coinId) {}
    public record ValuationResult(String vsCurrency, List<Line> lines, BigDecimal total, List<String> unresolved) {}


    /** Value by (exchange, asset), plus per-exchange subtotals and pie by asset. */
    public Dashboard valueDashboard(String account, String vsCurrency) {
        vsCurrency = vsCurrency.toLowerCase(Locale.ROOT);

        // latest balances per exchange + asset
        record Row(String exchange, String asset, BigDecimal qty) {}
        var rows = jdbc.query("""
            select exchange, asset, sum(total_amt) as qty
            from v_latest_balance
            where account = ?
            group by exchange, asset
            order by exchange, asset
            """,
                (ResultSet rs, int i) -> new Row(
                        rs.getString("exchange"),
                        rs.getString("asset"),
                        rs.getBigDecimal("qty")
                ),
                account
        );

        // resolve all symbols once, fetch prices once
        var symbols = rows.stream().map(r -> r.asset).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String,String> symToId = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (var s : symbols) {
            resolver.resolve(s).ifPresentOrElse(id -> symToId.put(s, id), () -> unresolved.add(s));
        }
        Map<String, BigDecimal> idToPrice = symToId.isEmpty()
                ? Map.of()
                : prices.getSimplePrice(new LinkedHashSet<>(symToId.values()), vsCurrency);

        // compute values
        Map<String, BigDecimal> platformSubtotal = new LinkedHashMap<>(); // exchange -> subtotal
        Map<String, BigDecimal> pieByAsset = new LinkedHashMap<>();        // asset -> value across platforms
        BigDecimal grand = BigDecimal.ZERO;

        for (var r : rows) {
            String id = symToId.get(r.asset);
            BigDecimal price = (id == null) ? null : idToPrice.get(id);
            if (price == null) continue; // skip unresolved for totals

            BigDecimal value = r.qty.multiply(price, MC);
            platformSubtotal.merge(r.exchange, value, (a,b) -> a.add(b, MC));
            pieByAsset.merge(r.asset, value, (a,b) -> a.add(b, MC));
            grand = grand.add(value, MC);
        }

        // pack for UI
        var subtotals = platformSubtotal.entrySet().stream()
                .map(e -> new PlatformTotal(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(PlatformTotal::exchange))
                .toList();

        var pie = pieByAsset.entrySet().stream()
                .sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed())
                .map(e -> new PieSlice(e.getKey(), e.getValue()))
                .toList();

        return new Dashboard(vsCurrency, subtotals, pie, grand, unresolved);
    }

    // DTOs for the dashboard
    public record PlatformTotal(String exchange, BigDecimal value) {}
    public record PieSlice(String asset, BigDecimal value) {}
    public record Dashboard(String vsCurrency,
                            List<PlatformTotal> platformTotals,
                            List<PieSlice> pie,
                            BigDecimal totalValue,
                            List<String> unresolvedSymbols) {}


}
