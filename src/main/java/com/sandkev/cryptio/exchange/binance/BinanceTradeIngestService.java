package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.portfolio.AssetUniverseDao;
import com.sandkev.cryptio.tx.TxUpserter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Ingests account trades from Binance for a (possibly dynamic) set of symbols.
 * Fixes:
 *  - Uses /api/v3/myTrades (was mistakenly calling a deposits endpoint).
 *  - Relies on the signed client for HMAC & recvWindow (no re-signing here).
 *  - Advances checkpoint using the actual max trade time seen.
 *  - Base/quote extraction recognizes many common quote assets (not just USDT).
 */
@Slf4j
@Service
public class BinanceTradeIngestService {

//    /** Your signed client should sign & call Binance. */
//    public interface BinanceSignedClient {
//        <T> T getSigned(String path, Map<String, Object> params, ParameterizedTypeReference<T> typeRef);
//    }

    private final BinanceSignedClient binanceClient;              // from BinanceSpotConfig
    private final TxUpserter writer;
    private final AssetUniverseDao assetsDao;
    private final BinanceSymbolMapper symbolMapper;
    private final IngestCheckpointDao ckpt;
    private final BinanceSymbolRegistry symbols;
    static final ParameterizedTypeReference<List<Map<String,Object>>> LIST_OF_MAP =
            new ParameterizedTypeReference<>() {};

    // Expanded list of common quote currencies on Binance (kept in descending popularity)
    private static final List<String> COMMON_QUOTES = List.of(
            "USDT","FDUSD","BUSD","USDC",
            "BTC","ETH","BNB",
            "EUR","GBP","TRY","AUD","BRL","ARS","MXN","ZAR","PLN","RUB","UAH","IDR","NGN","SAR","AED","JPY","CAD","CHF","INR"
    );
    private static final List<String> QUOTE_PREF = List.of("USDT", "FDUSD", "USDC", "BTC");

    // Build a regex for stripping any known quote suffix to get base
    private static final Pattern QUOTE_SUFFIX =
            Pattern.compile("(" + String.join("|", COMMON_QUOTES) + ")$");

    public BinanceTradeIngestService(
            @Qualifier("binanceSignedClient") BinanceSignedClient binanceClient,
            TxUpserter writer,
            AssetUniverseDao assetsDao,
            BinanceSymbolMapper symbolMapper,
            IngestCheckpointDao ckpt,
            BinanceSymbolRegistry symbols
    ) {
        this.binanceClient = binanceClient;
        this.writer = writer;
        this.assetsDao = assetsDao;
        this.symbolMapper = symbolMapper;
        this.ckpt = ckpt;
        this.symbols = symbols;
    }

    /**
     * Ingest trades for ALL discovered assets for this account/exchange using a dynamic symbol list.
     * Falls back to symbolMapper.toMarket(asset) to build symbols (so it compiles with your current mapper).
     */
    public int ingestAllAssets(String accountRef, @Nullable Instant sinceInclusive) {
        String exchange = "binance";
        Set<String> assets = assetsDao.assetsForAccount(exchange, accountRef);

        List<String> symbols = new ArrayList<>();
        for (String a : assets) {
            // Today: rely on existing mapper (likely returns a<quote> with USDT), so this compiles unchanged.
            // Future: switch to an exchangeInfo-backed mapper: symbolsForBase(a) filtered by preferred quotes.
            String mkt = symbolMapper.toMarket(a);
            if (mkt != null && !mkt.isBlank()) {
                symbols.add(mkt);
            }
        }

        if (symbols.isEmpty()) {
            log.info("No assets discovered for account {} on {}. Nothing to ingest.", accountRef, exchange);
            return 0;
        }

        int total = 0;
        for (String symbol : symbols) {
            try {
                total += fetchAndUpsertTradesForSymbol(symbol, accountRef, sinceInclusive);
            } catch (RuntimeException ex) {
                String msg = ex.getMessage();
                if (msg != null && msg.contains("\"code\":-1121")) {
                    log.warn("Skipping invalid/unsupported symbol on Binance: {} ({})", symbol, msg);
                    continue;
                }
                log.error("Trade ingest failed for symbol {}: {}", symbol, msg, ex);
                // continue other symbols
            }
        }
        return total;
    }

    /**
     * Fetches trades for a single symbol using /api/v3/myTrades and upserts into TxWriter.
     * Uses fromId pagination; startTime is used only for the first page (if provided/available).
     */
    private int fetchAndUpsertTradesForSymbol(String maybeBaseOrPair,
                                              String accountRef,
                                              @Nullable Instant sinceInclusive) {
        // 0) normalize / resolve symbol
        String raw = normalizeSymbolLike(maybeBaseOrPair);

        // quick skip of obvious composites (e.g. RUNEMOVR)
        if (raw.length() > 6 && !symbols.isValid(raw)) {
            log.warn("Skipping invalid/composite asset/symbol: {}", raw);
            return 0;
        }

        String symbol = raw;
        if (!symbols.isValid(symbol)) {
            var resolved = symbols.resolve(raw);  // registry uses its own quote pref
            if (resolved.isEmpty()) {
                log.warn("No tradable spot pair found for base {}", raw);
                return 0;
            }
            symbol = resolved.get();
        }

        // 1) per-symbol checkpoint
        String kind = ckKey(symbol);
        long startMs = (sinceInclusive != null ? sinceInclusive
                : ckpt.get("binance", accountRef, kind).orElse(Instant.EPOCH)).toEpochMilli();

        Long fromId = null; // we’ll switch to this after page 1
        int total = 0;

        for (int page = 0; page < 10_000; page++) { // generous safety cap
            var params = new LinkedHashMap<String, Object>();
            params.put("symbol", symbol);
            params.put("limit", 1000);

            if (fromId != null) {
                // paging by id only
                params.put("fromId", fromId);
            } else if (startMs > 0) {
                // seed first page by time (optional)
                params.put("startTime", startMs);
            }

            List<Map<String, Object>> trades;
            try {
                trades = callMyTrades(params); // uses your helper for error mapping
            } catch (RuntimeException ex) {
                String msg = ex.getMessage();
                if (msg != null && msg.contains("\"code\":-1121")) {
                    log.warn("Binance rejected symbol {} as invalid; skipping.", symbol);
                    return 0;
                }
                throw ex;
            }

            if (trades == null || trades.isEmpty()) {
                // nothing more for this symbol
                break;
            }
            log.info("saving {} rows of trades", trades.size());

            long maxTime = startMs;
            long lastSeenId = -1L;

            for (Map<String, Object> t : trades) {
                long id   = ((Number) t.get("id")).longValue();
                long time = ((Number) t.get("time")).longValue();

                lastSeenId = Math.max(lastSeenId, id);
                maxTime    = Math.max(maxTime, time);

                String commissionAsset = (String) t.get("commissionAsset");
                BigDecimal qty         = new BigDecimal(String.valueOf(t.get("qty")));
                BigDecimal price       = new BigDecimal(String.valueOf(t.get("price")));
                BigDecimal commission  = new BigDecimal(String.valueOf(t.getOrDefault("commission", "0")));
                boolean isBuyer        = Boolean.TRUE.equals(t.get("isBuyer"));

                // derive base/quote from resolved symbol
                String base  = stripKnownQuote(symbol);
                String quote = symbol.substring(base.length());

                writer.upsert(
                        "binance", accountRef,
                        base, quote,
                        isBuyer ? "BUY" : "SELL",
                        qty, price,
                        commission, commissionAsset,
                        Instant.ofEpochMilli(time),
                        "trade:" + symbol + ":" + id
                );
                total++;
            }

            // advance checkpoint for this symbol
            if (maxTime > startMs) {
                ckpt.put("binance", accountRef, kind, Instant.ofEpochMilli(maxTime), null);
                startMs = maxTime;
            }

            // advance page cursor
            if (lastSeenId >= 0) {
                fromId = lastSeenId + 1;
            } else {
                break; // defensive
            }

            // done if short page
            if (trades.size() < 1000) break;
        }

        return total;
    }

    private List<Map<String,Object>> callMyTrades(Map<String, Object> params) {
        try {
            // Correct endpoint, signed by the client
            return binanceClient.get("/api/v3/myTrades", params, LIST_OF_MAP);
        } catch (RuntimeException ex) {
            String msg = ex.getMessage();
            if (msg != null && msg.contains("\"code\":-1121")) { // invalid symbol
                log.warn("Binance rejected symbol: {}", msg);
                return List.of();
            }
            if (msg != null && msg.contains("\"code\":-1022")) { // signature not valid
                throw new IllegalStateException("Signature not valid (check secret, query ordering, timestamp). Body=" + msg, ex);
            }
            if (msg != null && msg.contains("\"code\":-1021")) { // timestamp out of range
                throw new IllegalStateException("Timestamp outside recvWindow (sync system clock or increase recvWindow). Body=" + msg, ex);
            }
            throw ex;
        }
    }

    /** Strip a known quote suffix from a symbol to get base; if none matches, return the original (best-effort). */
    private static String stripKnownQuote(String symbol) {
        var m = QUOTE_SUFFIX.matcher(symbol);
        if (m.find()) {
            int start = m.start();
            return symbol.substring(0, start);
        }
        // Fallback: if no known quote matched, return original (writer will still get a consistent external_id)
        return symbol;
    }

    // ==== Collaborators (interfaces you already have) ========================
    private static String ckKey(String symbol) { return "trades:" + symbol; }

    private static String normalizeSymbolLike(String s) {
        return s.replace("/", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
    }


}
