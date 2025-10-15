package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxUpserter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class BinanceConvertIngestService {

    private static final ParameterizedTypeReference<Map<String,Object>> MAP_OF_STRING_OBJECT =
            new ParameterizedTypeReference<>() {};
    private static final long NINETY_DAYS_MS = Duration.ofDays(90).toMillis();

    private final BinanceSignedClient client;
    private final IngestCheckpointDao ckpt;
    private final TxUpserter tx;

    public BinanceConvertIngestService(BinanceSignedClient client,
                                       IngestCheckpointDao ckpt,
                                       TxUpserter tx) {
        this.client = client;
        this.ckpt = ckpt;
        this.tx = tx;
    }

    /** Ingest Binance Convert history (sapi/v1/convert/tradeFlow). */
    public int ingest(String accountRef, Instant sinceInclusive) {
        long startMs = (sinceInclusive != null)
                ? sinceInclusive.toEpochMilli()
                : ckpt.get("binance", accountRef, "convert")
                      .map(Instant::toEpochMilli)
                      .orElse(0L);

        int inserted = 0;
        long windowStart = startMs;
        final long now = System.currentTimeMillis();

        for (int page = 0; page < 500 && windowStart <= now; page++) {
            long windowEnd = Math.min(windowStart + NINETY_DAYS_MS - 1, now);

            var params = new LinkedHashMap<String,Object>();
            params.put("startTime", windowStart);
            params.put("endTime",   windowEnd);
            params.put("limit",     500); // max page size supported

            Map<String, Object> root = null;
            int attempts = 0;
            for (;;) {
                try {
                    root = client.get("/sapi/v1/convert/tradeFlow", params, MAP_OF_STRING_OBJECT);
                    break; // success
                } catch (RuntimeException ex) {
                    if (!isRateLimit(ex) || attempts >= 6) throw ex;
                    long sleep = nextBackoffMs(attempts++);
                    // optional: log current weights if you surface headers (see client change below)
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ignore) {
                        //do nothing
                    }
                    // then loop and retry
                }
            }

            if (root == null) {
                // treat as empty window; advance
                windowStart = windowEnd + 1;
                continue;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) root.getOrDefault("rows", List.of());

            if (rows.isEmpty()) {
                // no data in this window: move to the next time window
                windowStart = windowEnd + 1;
                continue;
            }

            long maxTs = windowStart;

            for (var r : rows) {
                // expected fields per Binance docs
                String orderId     = String.valueOf(r.get("orderId"));
                long   createTime  = ((Number) r.get("createTime")).longValue();
                String fromAsset   = String.valueOf(r.get("fromAsset"));
                String toAsset     = String.valueOf(r.get("toAsset"));
                BigDecimal fromAmt = new BigDecimal(String.valueOf(r.get("fromAmount")));
                BigDecimal toAmt   = new BigDecimal(String.valueOf(r.get("toAmount")));

                Instant ts = Instant.ofEpochMilli(createTime);

                // Write two legs: out and in (idempotency on external_id)
                tx.upsert("binance", accountRef, fromAsset, "N/A",
                          "CONVERT_OUT", fromAmt, null,
                          BigDecimal.ZERO, null, ts,
                          "convert:out:" + orderId);

                tx.upsert("binance", accountRef, toAsset, "N/A",
                          "CONVERT_IN", toAmt, null,
                          BigDecimal.ZERO, null, ts,
                          "convert:in:" + orderId);

                inserted += 2;
                if (createTime > maxTs) maxTs = createTime;
            }

            // checkpoint at highest seen; continue from max+1
            ckpt.put("binance", accountRef, "convert", Instant.ofEpochMilli(maxTs), null);
            windowStart = (maxTs <= windowStart) ? (windowEnd + 1) : (maxTs + 1);
        }

        return inserted;
    }

    private static boolean isRateLimit(RuntimeException ex) {
        String m = ex.getMessage();
        return m != null && (m.contains("429") || m.contains("\"code\":-1003"));
    }

    private static long nextBackoffMs(int attempt) {
        // exponential backoff with jitter: 500ms, 1s, 2s, 4s, 8s...
        long base = (long) (500 * Math.pow(2, attempt));  // cap later
        long jitter = ThreadLocalRandom.current().nextLong(200, 600);
        return Math.min(10_000, base + jitter);           // cap at 10s
    }

}
