package com.sandkev.cryptio.trades;

// src/main/java/com/sandkev/cryptio/binance/BinanceTransferIngestService.java
//package com.sandkev.cryptio.binance;

import com.sandkev.cryptio.config.BinanceSpotProperties;
import com.sandkev.cryptio.spot.BinanceSignedClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class BinanceTransferIngestService {

    private static final ParameterizedTypeReference<List<Map<String,Object>>> LIST_OF_MAP =
            new ParameterizedTypeReference<>() {};
    private final BinanceSignedClient binanceSignedClient;
    private final BinanceSpotProperties props;
    private final JdbcTemplate jdbc;

    public BinanceTransferIngestService(BinanceSignedClient binanceSignedClient,
                                        BinanceSpotProperties props,
                                        JdbcTemplate jdbc) {
        this.binanceSignedClient = binanceSignedClient;
        this.props = props;
        this.jdbc = jdbc;
    }

    /** Pull recent DEPOSITS since the given instant (uses ingest_checkpoint kind='deposits'). */
    public int ingestDeposits(String accountRef, Instant sinceInclusive) {
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli() : 0L;

        // resume from checkpoint if newer
        var ck = jdbc.query(
                "select cursor_ts from ingest_checkpoint where exchange='binance' and account_ref=? and kind='deposits'",
                (rs,i) -> rs.getTimestamp(1).toInstant(),
                accountRef
        );
        if (!ck.isEmpty() && (sinceInclusive == null || ck.getFirst().isAfter(sinceInclusive))) {
            startMs = ck.getFirst().toEpochMilli();
        }

        int inserted = 0;
        long pageStart = startMs;
        for (int page = 0; page < 200; page++) {


            long timestamp = System.currentTimeMillis();
            long recvWindow = 60_000L;

            LinkedHashMap<String,Object> p = new LinkedHashMap<>();
            p.put("startTime", String.valueOf(pageStart));               // only if you want incremental paging
            p.put("timestamp", String.valueOf(timestamp));
            p.put("recvWindow", String.valueOf(recvWindow));

            String qs  = canonicalQuery(p);        // exactly this string…
            String sig = signHmacSHA256(qs);
            String uri = "/sapi/v1/capital/deposit/hisrec?" + qs + "&signature=" + sig;

            // Binance returns a top-level array
            List<Map<String, Object>> rows = binanceSignedClient.get(
                    "/sapi/v1/capital/deposit/hisrec",
                    p,
                    LIST_OF_MAP
            );
            if (rows == null || rows.isEmpty()) break;

            long maxTs = pageStart;
            for (var r : rows) {
                // Example fields (Binance): coin, amount, insertTime, txId, status, network, wallet
                String coin = (String) r.get("coin");
                BigDecimal amount = new BigDecimal((String) r.get("amount"));
                long insertTime = ((Number) r.get("insertTime")).longValue();
                String txId = (String) r.get("txId");

                // Only finalized status? (status: 1=success as of current docs; but keep all >=1)
                // If you want to filter: Integer status = ((Number) r.get("status")).intValue();

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
/*
                // externalId: deposit:<coin>:<txId>:<insertTime>
                writer.upsert("binance", accountRef, coin, "N/A",
                        "DEPOSIT",
                        new BigDecimal((String)r.get("amount")),
                        null, null, null,
                        Instant.ofEpochMilli(((Number)r.get("insertTime")).longValue()),
                        "deposit:" + coin + ":" + (String)r.get("txId") + ":" + r.get("insertTime")
                );
*/


                inserted++;
                if (insertTime > maxTs) maxTs = insertTime;
            }

            // checkpoint
            jdbc.update("""
                merge into ingest_checkpoint (exchange, account_ref, kind, cursor_str, cursor_ts, updated_at)
                key (exchange, account_ref, kind)
                values ('binance', ?, 'deposits', null, ?, current_timestamp)
            """, accountRef, new java.sql.Timestamp(maxTs));

            if (maxTs <= pageStart) break;
            pageStart = maxTs + 1;
        }
        return inserted;
    }

    /** Pull recent WITHDRAWALS (includes withdrawal fee deducted in coin). */
    public int ingestWithdrawals(String accountRef, Instant sinceInclusive) {
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli() : 0L;

        var ck = jdbc.query(
                "select cursor_ts from ingest_checkpoint where exchange='binance' and account_ref=? and kind='withdrawals'",
                (rs,i) -> rs.getTimestamp(1).toInstant(),
                accountRef
        );
        if (!ck.isEmpty() && (sinceInclusive == null || ck.getFirst().isAfter(sinceInclusive))) {
            startMs = ck.getFirst().toEpochMilli();
        }

        int inserted = 0;
        long pageStart = startMs;
        for (int page = 0; page < 200; page++) {
            Map<String, Object> params = Map.of(
                    "startTime", Long.toString(pageStart),
                    "limit", "1000",
                    "timestamp", "" + System.currentTimeMillis(),
                    "recvWindow", "" +  props.recvWindow()
            );

            final long pageStartOne = pageStart;
            List<Map<String, Object>> rows = binanceSignedClient.get(
                    "/sapi/v1/capital/deposit/hisrec",
                    params,
                    LIST_OF_MAP
            );
            if (rows == null || rows.isEmpty()) break;

            long maxTs = pageStart;
            for (var r : rows) {
                // Example fields: id, coin, amount, transactionFee, applyTime, txId, status
                String id = String.valueOf(r.get("id"));
                String coin = (String) r.get("coin");
                BigDecimal amount = new BigDecimal((String) r.get("amount"));
                BigDecimal fee    = new BigDecimal(String.valueOf(r.getOrDefault("transactionFee", "0")));
                String applyTimeStr = String.valueOf(r.get("applyTime")); // e.g. "2023-08-01 12:34:56"
                long ts = parseApplyTimeMillis(applyTimeStr);
                // Upsert WITHDRAW (quantity is the amount transferred out), and record fee in same coin
                jdbc.update("""
                    merge into tx (exchange, account_ref, base, quote, type, quantity, price, fee, fee_asset, ts, external_id)
                    key (exchange, external_id)
                    values (?, ?, ?, ?, 'WITHDRAW', ?, null, ?, ?, ?, ?)
                """,
                        "binance", accountRef, coin, "N/A", amount, fee, coin,
                        new java.sql.Timestamp(ts),
                        "withdraw:" + id
                );

                //TODO: use txWriter
/*
                // externalId: withdraw:<id>
                writer.upsert("binance", accountRef, coin, "N/A",
                        "WITHDRAW",
                        new BigDecimal((String)r.get("amount")),
                        null,
                        new BigDecimal(String.valueOf(r.getOrDefault("transactionFee","0"))),
                        coin,
                        parseApplyTime((String)r.get("applyTime")),
                        "withdraw:" + String.valueOf(r.get("id"))
                );
*/

                inserted++;
                if (ts > maxTs) maxTs = ts;
            }

            jdbc.update("""
                merge into ingest_checkpoint (exchange, account_ref, kind, cursor_str, cursor_ts, updated_at)
                key (exchange, account_ref, kind)
                values ('binance', ?, 'withdrawals', null, ?, current_timestamp)
            """, accountRef, new java.sql.Timestamp(maxTs));

            if (maxTs <= pageStart) break;
            pageStart = maxTs + 1;
        }
        return inserted;
    }

    // --- helpers ---
    private long parseApplyTimeMillis(String s) {
        try {
            // Binance returns "yyyy-MM-dd HH:mm:ss"
            var dt = java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return dt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception e) {
            // sometimes it's millis already
            try { return Long.parseLong(s); } catch (Exception ignored) { return System.currentTimeMillis(); }
        }
    }

    private record Signed(long timestamp, long recvWindow, String signature) {}
    private Signed sign(Map<String,String> params) {
        long timestamp = System.currentTimeMillis();
        long recvWindow = props.recvWindow();
        var sb = new StringBuilder();
        for (var e : params.entrySet()) {
            if (e.getValue() != null) sb.append(e.getKey()).append('=').append(e.getValue()).append('&');
        }
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

    static String canonicalQuery(Map<String, Object> params) {
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

    String signHmacSHA256(String payload) {
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


}
