// src/main/java/com/sandkev/cryptio/exchange/binance/ingest/TimeWindowIngest.java
package com.sandkev.cryptio.exchange.binance.ingest;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxUpserter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sandkev.cryptio.exchange.binance.ingest.IdCursorIngest.TRADE_HISTORY_START;

@Slf4j
@RequiredArgsConstructor
public abstract class TimeWindowIngest<T> {
    protected final BinanceSignedClient client;
    protected final IngestCheckpointDao ckpt;
    protected final TxUpserter tx;

    /** e.g. "convert", "dust", "rewards", "deposits", "withdrawals" */
    protected abstract String kind();

    /** Endpoint path, e.g. /sapi/v1/convert/tradeFlow */
    protected abstract String path();

    /** Size of each time window. */
    protected Duration windowSize() { return Duration.ofDays(90); }

    /** Additional constant params (e.g. limit). */
    protected void addConstantParams(Map<String,Object> p) {}

    /** Fetch one window. Can return list OR wrap in an object then pick rows. */
    protected abstract List<T> fetch(long startMs, long endMs);

    /** Map one row into 1..N tx.upsert(...) calls; return event timestamp (millis) for checkpoint. */
    protected abstract RowResult handleRow(T row, String accountRef);

    /** Optional pacing before each call. */
    protected long preCallPauseMs() { return 250L; }

    public int ingest(String accountRef, Instant sinceInclusive) {
        final String EX = "binance";
        long startMs = Checkpoints.startMs(ckpt, EX, accountRef, kind(), sinceInclusive);
        startMs = Math.max(startMs, TRADE_HISTORY_START);//not before earliest possible start date
        final long now = System.currentTimeMillis();

        int inserted = 0;
        long windowStart = startMs;

        for (int page = 0; page < 10_000 && windowStart <= now; page++) {
            long windowEnd = Math.min(now, windowStart + windowSize().toMillis() - 1);

            try {
                log.info("pausing [{}] before making request {}", preCallPauseMs(),
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(windowStart), ZoneOffset.UTC));
                RateLimit.beforeCall(preCallPauseMs());
                List<T> rows = fetch(windowStart, windowEnd);
                if (rows == null || rows.isEmpty()) {
                    windowStart = windowEnd + 1;
                    continue;
                }

                long maxTs = windowStart;
                for (T r : rows) {
                    RowResult rr = handleRow(r, accountRef);
                    inserted += rr.inserted();
                    if (rr.eventTsMillis() > 0) {
                        maxTs = Math.max(maxTs, rr.eventTsMillis());
                    }
                }

                Checkpoints.save(ckpt, EX, accountRef, kind(), maxTs);
                windowStart = (maxTs <= windowStart) ? (windowEnd + 1) : (maxTs + 1);
            } catch (RuntimeException e) {
                log.warn("Ingest '{}' window [{},{}] failed: {}", kind(), windowStart, windowEnd, e.toString());
                // If your client exposes headers, you can pass them to RateLimit.afterError(...)
                RateLimit.afterError(/*headers*/ null, /*fallback*/ 2_000);
                // advance cautiously to avoid tight loops
                windowStart = windowEnd + 1;
            }
        }
        return inserted;
    }

    /** Small helper for building the window param map. */
    protected Map<String,Object> baseParams(long startMs, long endMs) {
        var p = new LinkedHashMap<String,Object>();
        p.put("startTime", startMs);
        p.put("endTime", endMs);
        addConstantParams(p);
        return p;
    }

    /** Handy caller for simple payloads. */
    protected <R> R call(Map<String,Object> params, ParameterizedTypeReference<R> type) {
        return client.get(path(), params, type);
    }
}
