package com.sandkev.cryptio.trades;

// src/main/java/com/sandkev/cryptio/binance/BinanceTransferIngestService.java
//package com.sandkev.cryptio.binance;

import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.spot.BinanceSignedClient;
import com.sandkev.cryptio.tx.TxUpserter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BinanceTransferIngestService {

    private static final String EXCHANGE = "binance";
    private static final Duration MAX_WINDOW = Duration.ofDays(90);

    private static final ParameterizedTypeReference<List<Map<String,Object>>> LIST_OF_MAP =
            new ParameterizedTypeReference<>() {};
    private final BinanceSignedClient client;
    private final TxUpserter tx;
    private final IngestCheckpointDao ckpt;
    private final JdbcTemplate jdbc;


    public BinanceTransferIngestService(BinanceSignedClient binanceSignedClient,
                                        TxUpserter tx,
                                        IngestCheckpointDao ckpt,
                                        JdbcTemplate jdbc) {
        this.client = binanceSignedClient;
        this.tx = tx;
        this.ckpt = ckpt;
        this.jdbc = jdbc;
    }

    /** Pull recent DEPOSITS since the given instant (checkpoint kind='deposits'). */
    public int ingestDeposits(String accountRef, Instant sinceInclusive) {
        //final long startMs = effectiveStartMs("deposits", sinceInclusive);
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli() :
                ckpt.get("binance", accountRef, "deposits").map(Instant::toEpochMilli).orElse(0L);
        final long nowMs = System.currentTimeMillis();

        int inserted = 0;
        long windowStart = startMs;

        for (int page = 0; page < 500 && windowStart <= nowMs; page++) {
            long windowEnd = Math.min(windowStart + MAX_WINDOW.toMillis() - 1, nowMs);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("startTime", windowStart);
            params.put("endTime",   windowEnd);
            params.put("limit",     1000);

            // Binance returns a top-level array
            List<Map<String, Object>> rows = client.get(
                    "/sapi/v1/capital/deposit/hisrec",
                    params,
                    LIST_OF_MAP
            );

            if (rows == null || rows.isEmpty()) {
                windowStart = windowEnd + 1;
                continue;
            }

            long maxTs = windowStart;

            for (var r : rows) {
                final String coin       = String.valueOf(r.get("coin"));
                final BigDecimal amount = new BigDecimal(String.valueOf(r.get("amount")));
                final long insertTime   = ((Number) r.get("insertTime")).longValue();
                final String txId       = String.valueOf(r.get("txId"));

                // Only finalized status? (status: 1=success as of current docs; but keep all >=1)
                // If you want to filter: Integer status = ((Number) r.get("status")).intValue();
                final String externalId = "deposit:" + coin + ":" + txId + ":" + insertTime;

                // Upsert into tx as DEPOSIT. external_id ensures idempotency.
                jdbc.update("""
                    merge into tx (exchange, account_ref, base, quote, type, quantity, price, fee, fee_asset, ts, external_id)
                    key (exchange, external_id)
                    values (?, ?, ?, ?, 'DEPOSIT', ?, null, 0, ?, ?, ?)
                """,
                        "binance", accountRef, coin, "N/A", amount, coin,
                        new java.sql.Timestamp(insertTime),
                        "deposit:" + coin + ":" + txId + ":" + insertTime
                );

                //TODO: use txWriter
//                tx.upsert(
//                        EXCHANGE, accountRef,
//                        coin, "N/A",
//                        "DEPOSIT",
//                        amount,
//                        null,
//                        BigDecimal.ZERO,
//                        coin,
//                        Instant.ofEpochMilli(insertTime),
//                        externalId
//                );

                inserted++;
                if (insertTime > maxTs) maxTs = insertTime;
            }

            // checkpoint at next millisecond after the last seen row
            //ckpt.saveSince(EXCHANGE+".deposits", maxTs + 1);
            ckpt.put("binance", accountRef, "deposits", Instant.ofEpochMilli(maxTs), null);

            windowStart = (maxTs <= windowStart) ? (windowEnd + 1) : (maxTs + 1);
        }

        return inserted;
    }

    /** Pull recent WITHDRAWALS (includes withdrawal fee deducted in coin). */
    public int ingestWithdrawals(String accountRef, Instant sinceInclusive) {
        final long startMs = effectiveStartMs("withdrawals", sinceInclusive);
        final long nowMs = System.currentTimeMillis();

        int inserted = 0;
        long windowStart = startMs;

        for (int page = 0; page < 500 && windowStart <= nowMs; page++) {
            long windowEnd = Math.min(windowStart + MAX_WINDOW.toMillis() - 1, nowMs);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("startTime", windowStart);
            params.put("endTime",   windowEnd);
            params.put("limit",     1000);

            // Top-level array here too
            List<Map<String, Object>> rows = client.get(
                    "/sapi/v1/capital/withdraw/history",
                    params,
                    LIST_OF_MAP
            );

            if (rows == null || rows.isEmpty()) {
                windowStart = windowEnd + 1;
                continue;
            }

            long maxTs = windowStart;

            for (var r : rows) {
                final String id        = String.valueOf(r.get("id"));
                final String coin      = String.valueOf(r.get("coin"));
                final BigDecimal amt   = new BigDecimal(String.valueOf(r.get("amount")));
                final BigDecimal fee   = new BigDecimal(String.valueOf(r.getOrDefault("transactionFee", "0")));
                final String applyTime = String.valueOf(r.get("applyTime")); // "yyyy-MM-dd HH:mm:ss" or millis
                final long ts          = parseApplyTimeMillis(applyTime);

                final String externalId = "withdraw:" + id;

                tx.upsert(
                        EXCHANGE, accountRef,
                        coin, "N/A",
                        "WITHDRAW",
                        amt,
                        null,
                        fee,
                        coin,
                        Instant.ofEpochMilli(ts),
                        externalId
                );

                inserted++;
                if (ts > maxTs) maxTs = ts;
            }

            ckpt.saveSince(EXCHANGE+ ".withdrawals", maxTs + 1);

            windowStart = (maxTs <= windowStart) ? (windowEnd + 1) : (maxTs + 1);
        }

        return inserted;
    }

    // ---- helpers ------------------------------------------------------------

    private long effectiveStartMs(String kind, Instant sinceInclusive) {
        long ck = ckpt.getSince(EXCHANGE+ "." + kind).orElse(0L);
        long si = sinceInclusive != null ? sinceInclusive.toEpochMilli() : 0L;
        long start = Math.max(ck, si);
        if (start == 0L) start = System.currentTimeMillis() - Duration.ofDays(90).toMillis() + 1;
        return start;
        // (If you want “prefer since unless checkpoint is newer”, this already does it.)
    }

    private static long parseApplyTimeMillis(String s) {
        try {
            var dt = java.time.LocalDateTime.parse(
                    s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return dt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            try { return Long.parseLong(s); } catch (Exception e) { return System.currentTimeMillis(); }
        }
    }

}
