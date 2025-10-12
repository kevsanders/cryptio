package com.sandkev.cryptio.trades;
// src/main/java/com/sandkev/cryptio/binance/BinanceTradeIngestService.java
//package com.sandkev.cryptio.binance;

import com.sandkev.cryptio.config.BinanceSpotProperties;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.portfolio.AssetUniverseDao;
import com.sandkev.cryptio.spot.BinanceSignedClient;
import com.sandkev.cryptio.tx.TxUpserter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Ingests Binance SPOT trades (/api/v3/myTrades) into tx table.
 * Idempotent via unique (exchange, external_id).
 */
@Service
@Slf4j
public class BinanceTradeIngestService {

    private final BinanceSignedClient binanceClient;              // from BinanceSpotConfig
    private final BinanceSpotProperties props; // has apiKey/secret/timeout
    private final JdbcTemplate jdbc;
    private final TxUpserter writer;
    private final AssetUniverseDao assetsDao;
    private final BinanceSymbolMapper symbolMapper;
    private final IngestCheckpointDao ckpt;


    private static final ParameterizedTypeReference<List<Map<String,Object>>> LIST_OF_MAP =
            new ParameterizedTypeReference<>() {};


    public BinanceTradeIngestService(@Qualifier("binanceSignedClient") BinanceSignedClient binanceClient, BinanceSpotProperties props,
                                     JdbcTemplate jdbc, TxUpserter writer, AssetUniverseDao assetsDao,
                                     BinanceSymbolMapper symbolMapper,
                                     IngestCheckpointDao ckpt) {
        this.binanceClient = binanceClient;
        this.props = props;
        this.jdbc = jdbc;
        this.writer = writer;
        this.assetsDao = assetsDao;
        this.symbolMapper = symbolMapper;
        this.ckpt = ckpt;
    }

    /** New: ingest trades for ALL discovered assets for this account/exchange using dynamic symbol list. */
    public int ingestAllAssets(String accountRef, @Nullable Instant sinceInclusive) {
        String exchange = "binance";
        Set<String> assets = assetsDao.assetsForAccount(exchange, accountRef);

        int total = 0;
        List<String> symbols = new ArrayList<>();
        for (String a : assets) {
            String mkt = symbolMapper.toMarket(a);
            if (mkt == null) continue;
            symbols.add(mkt);
        }

        if (symbols.isEmpty()) {
            log.info("No assets discovered for account {} on {}. Nothing to ingest.", accountRef, exchange);
            return 0;
        }

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
                // Optionally continue; or rethrow to fail the whole batch.
            }
        }
        return total;
    }


    private int fetchAndUpsertTradesForSymbol(String symbol, String accountRef, Instant sinceInclusive) /*throws SymbolNotFound*/ {
        //long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli() : 0L;

//        // Optional checkpoint: if we have a cursor_ts newer than given since, use it
//        var ck = jdbc.query(
//                "select cursor_ts from ingest_checkpoint where exchange='binance' and account_ref=? and kind='trades'",
//                (rs,i) -> rs.getTimestamp(1).toInstant(),
//                accountRef
//        );
//        if (!ck.isEmpty() && (sinceInclusive == null || ck.getFirst().isAfter(sinceInclusive))) {
//            startMs = ck.getFirst().toEpochMilli();
//        }
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli() :
                ckpt.get("binance", accountRef, "trades").map(Instant::toEpochMilli).orElse(0L);




        int total = 0;
        Long fromId = null;
        for (int page = 0; page < 500; page++) { // safety cap
            var params = new LinkedHashMap<String, String>();
            params.put("symbol", symbol);
            if (startMs > 0) params.put("startTime", Long.toString(startMs));
            params.put("limit", "1000");
            if (fromId != null) params.put("fromId", Long.toString(fromId));

            final Long fromIdOne = fromId;
            final long startMsOne = startMs;
            var signed = sign(params);
            List<Map<String,Object>> trades = callMyTrades(symbol, startMs > 0 ? startMs : null, 1000, fromId);

            if (trades == null) trades = List.of();
            if (trades.isEmpty()) break;

            long maxTime = startMs;
            long lastId = -1L;

            // empty + first page could mean symbol not found for the account/market
            if (trades.isEmpty() && page == 0 && fromId == null && startMs == 0L) {
                // ambiguous—could be no trades or symbol invalid. We'll treat truly invalid when Binance returns error.
                return total;
            }

            long maxTradeTime = startMs;
            for (Map<String, Object> t : trades) {
                // Example fields: id, orderId, price, qty, commission, commissionAsset, time, isBuyer, isMaker
                long id = ((Number) t.get("id")).longValue();
                long time = ((Number) t.get("time")).longValue();
                lastId = Math.max(lastId, id);
                maxTime = Math.max(maxTime, time);

                String commissionAsset = (String) t.get("commissionAsset");
                BigDecimal qty = new BigDecimal((String) t.get("qty"));
                BigDecimal price = new BigDecimal((String) t.get("price"));
                BigDecimal commission = new BigDecimal((String) t.getOrDefault("commission", "0"));
                boolean isBuyer = Boolean.TRUE.equals(t.get("isBuyer"));

                String base = symbol.replaceFirst("(USDT|BUSD|USDC|BTC|BNB|ETH|GBP|EUR)$", "");
                String quote = symbol.substring(base.length());

/*
                // Upsert into tx (H2 MERGE). Types: BUY/SELL
                jdbc.update("""
                    merge into tx (exchange, account_ref, base, quote, type, quantity, price, fee, fee_asset, ts, external_id)
                    key (exchange, external_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                        "binance", accountRef, base, quote,
                        isBuyer ? "BUY" : "SELL",
                        qty, price, commission, commissionAsset,
                        new java.sql.Timestamp(time),
                        "trade:" + symbol + ":" + id
                );
*/


                //todo: use TxWriter
                // externalId: trade:<symbol>:<id>
                writer.upsert("binance", accountRef, base, quote,
                        isBuyer ? "BUY" : "SELL",
                        new BigDecimal((String)t.get("qty")),
                        new BigDecimal((String)t.get("price")),
                        new BigDecimal(String.valueOf(t.getOrDefault("commission","0"))),
                        (String)t.get("commissionAsset"),
                        Instant.ofEpochMilli(((Number)t.get("time")).longValue()),
                        "trade:" + symbol + ":" + ((Number)t.get("id")).longValue()
                );

                total++;

                // For next page: use fromId only (no startTime)
                if (lastId >= 0) {
                    fromId = lastId + 1;
                } else {
                    break; // defensive
                }
            }

//            // Update checkpoint after each page
//            if (maxTradeTime > startMs) {
//                jdbc.update("""
//                    merge into ingest_checkpoint (exchange, account_ref, kind, cursor_str, cursor_ts, updated_at)
//                    key (exchange, account_ref, kind)
//                    values ('binance', ?, 'trades', ?, ?, current_timestamp)
//                """, accountRef, String.valueOf(fromId), new java.sql.Timestamp(maxTradeTime));
//                startMs = maxTradeTime;
//            }
            ckpt.put("binance", accountRef, "trades", Instant.ofEpochMilli(maxTradeTime), null);

            if (trades.isEmpty()) break; // done
        }
        return total;
    }

    private List<Map<String,Object>> callMyTrades(String symbol, Long startTimeMs, Integer limit, Long fromId) {
        long timestamp = System.currentTimeMillis();
        long recvWindow = 60_000L; // 60s is fine; smaller also OK if your clock is accurate

        // IMPORTANT: fromId is mutually exclusive with start/end time for /api/v3/myTrades
        boolean useFromIdOnly = (fromId != null);

        LinkedHashMap<String,Object> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        if (!useFromIdOnly && startTimeMs != null && startTimeMs > 0) {
            p.put("startTime", String.valueOf(startTimeMs));
            // (Optional) endTime can be added too, but not necessary
        }
        if (useFromIdOnly) {
            p.put("fromId", String.valueOf(fromId));
        }
        if (limit != null) p.put("limit", String.valueOf(limit));
        p.put("timestamp", String.valueOf(timestamp));
        p.put("recvWindow", String.valueOf(recvWindow));

        String qs = canonicalQuery(p);              // exactly what we sign…
        String sig = signHmacSHA256(qs);
        String uri = "/api/v3/myTrades?" + qs + "&signature=" + sig;  // …exactly what we send

        try {
            log.debug("Fetching trades for symbol={} (uri={})", symbol, uri);
            @SuppressWarnings("unchecked")
            // Binance returns a top-level array
            List<Map<String,Object>> out = binanceClient.get(
                    "/sapi/v1/capital/deposit/hisrec",
                    p,
                    LIST_OF_MAP
            );
            return out == null ? List.of() : out;
        } catch (RuntimeException ex) {
            // Optional: detect common Binance error codes in body
            String msg = ex.getMessage();
            if (ex.getMessage().contains("\"code\":-1121")) {
                log.warn("Binance rejected symbol {}: {}", symbol, ex.getMessage());
                return List.of(); // skip gracefully
            }
            if (msg != null && msg.contains("\"code\":-1022")) { // signature not valid
                throw new IllegalStateException("Signature not valid (check API key/secret, query ordering, and timestamp). Body=" + msg, ex);
            }
            if (msg != null && msg.contains("\"code\":-1021")) { // timestamp out of range
                throw new IllegalStateException("Timestamp outside recvWindow (sync system clock or increase recvWindow). Body=" + msg, ex);
            }
            throw ex;
        }
    }

    private static String canonicalQuery(Map<String, Object> params) {
        // Keep stable order: symbol, startTime, limit, fromId, timestamp, recvWindow
        StringBuilder sb = new StringBuilder(128);
        boolean first = true;
        for (var e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append('&'); first = false;
            // Binance expects standard URL encoding of values, keys are plain ASCII
            sb.append(e.getKey()).append('=').append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String signHmacSHA256(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sig.length * 2);
            for (byte b : sig) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    // ---- signing ----
    private record Signed(long timestamp, long recvWindow, String signature) {}
    private Signed sign(Map<String,String> params) {
        long timestamp = System.currentTimeMillis();
        long recvWindow = props.recvWindow();
        var sb = new StringBuilder();
        params.forEach((k,v) -> { if (v != null) sb.append(k).append('=').append(v).append('&'); });
        sb.append("timestamp=").append(timestamp).append("&recvWindow=").append(recvWindow);
        String query = sb.toString();

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(query.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(sig.length * 2);
            for (byte b : sig) hex.append(String.format("%02x", b));
            return new Signed(timestamp, recvWindow, hex.toString());
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private static class SymbolNotFound extends Exception {}
}
