package com.sandkev.cryptio.portfolio;

import com.sandkev.cryptio.price.CoinGeckoPriceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PortfolioValuationService {

    private final JdbcTemplate jdbc;
    private final CoinGeckoPriceService prices;

    public PortfolioValuationService(JdbcTemplate jdbc, CoinGeckoPriceService prices) {
        this.jdbc = jdbc;
        this.prices = prices;
    }

    /** Row projected from v_latest_balance */
    public record BalanceRow(String platform, String asset, BigDecimal qty) {}

    /** Dashboard DTOs */
    public record PlatformTotal(String platform, BigDecimal value) {}
    public record TokenSlice(String asset, BigDecimal value) {}
    public record PieData(List<String> labels, List<BigDecimal> values) {}

    private static final MathContext MC = MathContext.DECIMAL64;

    /** Load latest balances (platform, asset, total_amt) for an account. */
    public List<BalanceRow> loadLatestBalances(String accountRef) {
        return jdbc.query("""
            select exchange as platform, asset, sum(total_amt) as qty
            from v_latest_balance
            where account = ?
            group by exchange, asset
        """, (rs, i) -> new BalanceRow(
                rs.getString("platform"),
                rs.getString("asset"),
                rs.getBigDecimal("qty")
        ), accountRef);
    }

    /** Per-platform totals (in vs fiat). */
    public List<PlatformTotal> platformTotals(String accountRef, String vs) {
        var rows = loadLatestBalances(accountRef);

        // symbols present in balances
        var symbols = rows.stream()
                .map(BalanceRow::asset)
                .filter(Objects::nonNull)
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // SYMBOL -> price map (via CoinGecko IDs)
        Map<String, BigDecimal> priceMap = priceBySymbol(symbols, vs);

        Map<String, BigDecimal> byPlatform = new LinkedHashMap<>();
        for (var r : rows) {
            var sym = r.asset() == null ? "" : r.asset().toUpperCase();
            var px = priceMap.getOrDefault(sym, BigDecimal.ZERO);
            var value = (r.qty() == null ? BigDecimal.ZERO : r.qty().multiply(px, MC));
            byPlatform.merge(
                    (r.platform() == null ? "unknown" : r.platform()),
                    value,
                    BigDecimal::add
            );
        }

        return byPlatform.entrySet().stream()
                .map(e -> new PlatformTotal(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(PlatformTotal::platform))
                .toList();
    }

    /** Top N tokens by value (vs fiat). */
    public List<TokenSlice> topTokens(String accountRef, String vs, int limit, Double minPct) {
        var rows = loadLatestBalances(accountRef);

        var symbols = rows.stream()
                .map(BalanceRow::asset)
                .filter(Objects::nonNull)
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, BigDecimal> priceMap = priceBySymbol(symbols, vs);

        Map<String, BigDecimal> byAsset = new LinkedHashMap<>();
        for (var r : rows) {
            var sym = r.asset() == null ? "" : r.asset().toUpperCase();
            var px = priceMap.getOrDefault(sym, BigDecimal.ZERO);
            var value = (r.qty() == null ? BigDecimal.ZERO : r.qty().multiply(px, MC));
            byAsset.merge(sym, value, BigDecimal::add);
        }

        BigDecimal grand = byAsset.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal threshold =
                (minPct != null && minPct > 0 && grand.signum() > 0)
                        ? grand.multiply(BigDecimal.valueOf(minPct), MC)
                        : BigDecimal.ZERO;

        return byAsset.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> new TokenSlice(e.getKey(), e.getValue()))
                .filter(s -> s.value().compareTo(threshold) >= 0)
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Pie labels & values; group small slices into OTHER if below minPct. */
    public PieData pieData(String accountRef, String vs, double minPct) {
        var rows = loadLatestBalances(accountRef);

        var symbols = rows.stream()
                .map(BalanceRow::asset)
                .filter(Objects::nonNull)
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, BigDecimal> priceMap = priceBySymbol(symbols, vs);

        Map<String, BigDecimal> byAsset = new LinkedHashMap<>();
        for (var r : rows) {
            var sym = r.asset() == null ? "" : r.asset().toUpperCase();
            var px = priceMap.getOrDefault(sym, BigDecimal.ZERO);
            var value = (r.qty() == null ? BigDecimal.ZERO : r.qty().multiply(px, MC));
            byAsset.merge(sym, value, BigDecimal::add);
        }

        BigDecimal grand = byAsset.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (grand.signum() == 0) return new PieData(List.of(), List.of());

        BigDecimal cutoff = grand.multiply(BigDecimal.valueOf(minPct), MC);

        List<String> labels = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        BigDecimal other = BigDecimal.ZERO;

        var entries = byAsset.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .toList();

        for (var e : entries) {
            if (e.getValue().compareTo(cutoff) >= 0) {
                labels.add(e.getKey());
                values.add(e.getValue());
            } else {
                other = other.add(e.getValue());
            }
        }
        if (other.signum() > 0) {
            labels.add("OTHER");
            values.add(other);
        }

        return new PieData(labels, values);
    }

    // ==================== helpers ====================

    /**
     * Convert ticker symbols to CoinGecko IDs, fetch simple prices, then map back to SYMBOL -> price.
     */
    private Map<String, BigDecimal> priceBySymbol(Collection<String> symbols, String vs) {
        if (symbols == null || symbols.isEmpty()) return Map.of();

        // SYMBOL -> id (fallback to lowercase)
        Map<String, String> symToId = new LinkedHashMap<>();
        for (String sym : symbols) {
            String id = toGeckoId(sym);
            if (id != null) symToId.put(sym, id);
        }

        if (symToId.isEmpty()) return Map.of();

        // Call CoinGecko with IDs
        Map<String, BigDecimal> idToPrice = prices.getSimplePrice(new LinkedHashSet<>(symToId.values()), vs.toLowerCase(Locale.ROOT));
        if (idToPrice == null) idToPrice = Map.of();

        // Map back: SYMBOL -> price
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (var e : symToId.entrySet()) {
            out.put(e.getKey(), idToPrice.getOrDefault(e.getValue(), BigDecimal.ZERO));
        }
        return out;
    }

    /**
     * Minimal symbol → CoinGecko ID mapping.
     * Extend this map or fetch from DB/config. Fallback is lowercase symbol (best-effort).
     */
    private static String toGeckoId(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String sym = symbol.toUpperCase(Locale.ROOT);
        // strip suffixes like ".F"
        int dot = sym.indexOf('.');
        if (dot > 0) sym = sym.substring(0, dot);

        // common direct mappings
        String id = STATIC_MAP.get(sym);
        if (id != null) return id;

        // fallback: lowercased symbol (works for many like BTC->btc? NO (bitcoin), ETH->eth? NO (ethereum))
        // Better: maintain a proper map per your earlier config.
        return FALLBACKS.getOrDefault(sym, sym.toLowerCase(Locale.ROOT));
    }

    // Populate with your earlier mapping list; a small starter set:
    private static final Map<String, String> STATIC_MAP = Map.ofEntries(
            Map.entry("BTC", "bitcoin"),
            Map.entry("ETH", "ethereum"),
            Map.entry("USDT", "tether"),
            Map.entry("USDC", "usd-coin"),
            Map.entry("BNB", "binancecoin"),
            Map.entry("ADA", "cardano"),
            Map.entry("XRP", "ripple"),
            Map.entry("SOL", "solana"),
            Map.entry("LTC", "litecoin"),
            Map.entry("LINK", "chainlink"),
            Map.entry("DOT", "polkadot"),
            Map.entry("AVAX", "avalanche-2"),
            Map.entry("LUNA", "terra-luna"),
            Map.entry("LUNC", "terra-luna-classic"),
            Map.entry("MATIC", "matic-network"),
            Map.entry("POL", "polygon-ecosystem-token")
            // …extend as needed
    );

    // For assets where symbol==id in practice (rare); leave empty or add cases.
    private static final Map<String, String> FALLBACKS = Map.of();
}
