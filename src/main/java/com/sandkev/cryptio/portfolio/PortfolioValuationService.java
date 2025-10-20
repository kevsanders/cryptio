package com.sandkev.cryptio.portfolio;

import com.sandkev.cryptio.price.CoinGeckoPriceService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PortfolioValuationService {

    private final BalanceViewDao balances;         // <-- use DAO, not JdbcTemplate
    private final CoinGeckoPriceService prices;

    public PortfolioValuationService(BalanceViewDao balances, CoinGeckoPriceService prices) {
        this.balances = balances;
        this.prices = prices;
    }

    /** Row we work with after pulling from DAO (canonical may be null). */
    public record BalanceRow(String platform, String assetCanonical, String assetRaw, BigDecimal qty) {}

    /** Dashboard DTOs */
    public record PlatformTotal(String platform, BigDecimal value) {}
    public record TokenSlice(String asset, BigDecimal value) {}
    public record PieData(List<String> labels, List<BigDecimal> values) {}

    private static final MathContext MC = MathContext.DECIMAL64;

    /** Load latest balances for an account (via DAO), optionally filter by exchange. */
    private List<BalanceRow> loadLatestFromDao(String accountRef, @Nullable String exFilter) {
        // latestLeftJoin(exchangeOpt, account) -> rows with: exchange, account, asset (canonical), assetRaw, total
        var rows = balances.latest(exFilter, accountRef);

        return rows.stream()
                .map(r -> new BalanceRow(
                        r.exchange(),                    // platform (binance/kraken/…)
                        r.asset(),                       // canonical (nullable if unmapped)
                        r.asset(),                       // raw from exchange
                        r.total()                        // total qty
                ))
                .toList();
    }

    /** Per-platform totals (in vs fiat). Unmapped assets contribute zero. */
    public List<PlatformTotal> platformTotals(String accountRef, String vs) {
        // No exchange filter here; totals want all platforms
        var rows = loadLatestFromDao(accountRef, null);

        // Only request prices for canonically-mapped symbols
        var symbols = rows.stream()
                .map(BalanceRow::assetCanonical)
                .filter(Objects::nonNull)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, BigDecimal> priceMap = priceBySymbol(symbols, vs);

        Map<String, BigDecimal> byPlatform = new LinkedHashMap<>();
        for (var r : rows) {
            String platform = (r.platform() == null ? "unknown" : r.platform());
            String sym = (r.assetCanonical() == null ? null : r.assetCanonical().toUpperCase(Locale.ROOT));

            BigDecimal px = (sym == null) ? BigDecimal.ZERO : priceMap.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal qty = (r.qty() == null ? BigDecimal.ZERO : r.qty());
            BigDecimal value = qty.multiply(px, MC);

            byPlatform.merge(platform, value, BigDecimal::add);
        }

        return byPlatform.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new PlatformTotal(e.getKey(), e.getValue()))
                .toList();
    }

    /** Top N tokens by value (vs fiat). Unmapped assets are ignored (value=0). */
    public List<TokenSlice> topTokens(String accountRef, String vs, int limit, Double minPct, @Nullable String exFilter) {
        // IMPORTANT: filter once, then use the filtered list for everything downstream
        var filteredRows = loadLatestFromDao(accountRef, normalizedExchangeFilter(exFilter));

        var symbols = filteredRows.stream()
                .map(BalanceRow::assetCanonical)
                .filter(Objects::nonNull)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, BigDecimal> priceMap = priceBySymbol(symbols, vs);

        Map<String, BigDecimal> byAsset = new LinkedHashMap<>();
        for (var r : filteredRows) {
            if (r.assetCanonical() == null) continue; // unmapped → zero value, skip
            String sym = r.assetCanonical().toUpperCase(Locale.ROOT);
            BigDecimal px  = priceMap.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal qty = (r.qty() == null ? BigDecimal.ZERO : r.qty());
            BigDecimal value = qty.multiply(px, MC);
            byAsset.merge(sym, value, BigDecimal::add);
        }

        BigDecimal grand = byAsset.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal threshold =
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

    /** Pie data; unmapped assets contribute zero and are not labeled. */
    public PieData pieData(String accountRef, String vs, double minPct, @Nullable String exFilter) {
        var filteredRows = loadLatestFromDao(accountRef, normalizedExchangeFilter(exFilter));

        var symbols = filteredRows.stream()
                .map(BalanceRow::assetCanonical)
                .filter(Objects::nonNull)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, BigDecimal> priceMap = priceBySymbol(symbols, vs);

        Map<String, BigDecimal> byAsset = new LinkedHashMap<>();
        for (var r : filteredRows) {
            if (r.assetCanonical() == null) continue; // unmapped → zero value, skip
            String sym = r.assetCanonical().toUpperCase(Locale.ROOT);
            BigDecimal px  = priceMap.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal qty = (r.qty() == null ? BigDecimal.ZERO : r.qty());
            BigDecimal value = qty.multiply(px, MC);
            byAsset.merge(sym, value, BigDecimal::add);
        }

        BigDecimal grand = byAsset.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (grand.signum() == 0) return new PieData(List.of(), List.of());

        BigDecimal cutoff = grand.multiply(BigDecimal.valueOf(minPct), MC);

        List<String> labels = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        BigDecimal other = BigDecimal.ZERO;

        var ordered = byAsset.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .toList();

        for (var e : ordered) {
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

    /** Normalize exchange filter: null/blank/"total" → null; else lowercase. */
    private static String normalizedExchangeFilter(@Nullable String exFilter) {
        if (exFilter == null) return null;
        if (exFilter.isBlank()) return null;
        if ("total".equalsIgnoreCase(exFilter)) return null;
        return exFilter.toLowerCase(Locale.ROOT);
    }

    /** Fetch prices by canonical symbol; unmapped symbols are not requested. */
    private Map<String, BigDecimal> priceBySymbol(Collection<String> symbols, String vs) {
        if (symbols == null || symbols.isEmpty()) return Map.of();

        Map<String, String> symToId = new LinkedHashMap<>();
        for (String sym : symbols) {
            String id = toGeckoId(sym);
            if (id != null && !id.isBlank()) symToId.put(sym, id);
        }
        if (symToId.isEmpty()) return Map.of();

        Map<String, BigDecimal> idToPrice =
                prices.getSimplePrice(new LinkedHashSet<>(symToId.values()), vs.toLowerCase(Locale.ROOT));
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
            Map.entry("RUNE", "thorchain"),
            Map.entry("LINK", "chainlink"),
            Map.entry("DOT", "polkadot"),
            Map.entry("AVAX", "avalanche-2"),
            Map.entry("LUNA", "terra-luna"),
            Map.entry("LUNC", "terra-luna-classic"),
            Map.entry("MATIC", "matic-network"),
            Map.entry("POL", "polygon-ecosystem-token"),
            Map.entry("GLMR", "moonbeam"),
            Map.entry("HDX", "hydradx"),
            Map.entry("KSM", "kusama"),
            Map.entry("SUPER", "superfarm"),       // SuperFarm
            Map.entry("THETA", "theta-token"),
            Map.entry("USD", "usd-coin"),          // treat as USD stablecoin
            Map.entry("WELL", "moonwell-artemis"), // Moonwell (WELL)
            Map.entry("MOVR", "moonriver")
            // …extend as needed
    );

    // For assets where symbol==id in practice (rare); leave empty or add cases.
    private static final Map<String, String> FALLBACKS = Map.of();
}
