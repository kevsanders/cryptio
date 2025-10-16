// src/main/java/com/sandkev/cryptio/exchange/binance/ingest/IdCursorIngest.java
package com.sandkev.cryptio.exchange.binance.ingest;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxUpserter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public abstract class IdCursorIngest<T> {

    public static final long TRADE_HISTORY_START =
            LocalDate.of(2017, 10, 1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli();

    protected final BinanceSignedClient client;
    protected final IngestCheckpointDao ckpt;
    protected final TxUpserter tx;

    protected abstract String kind();      // e.g. "trades:BTCUSDT"
    protected abstract String path();      // e.g. /api/v3/myTrades
    protected abstract String symbol();    // resolved Binance symbol for this run

    /** seed time for the first page (optional) */
    protected abstract long startTimeMs();

    protected abstract RowResult handleRow(T row, String accountRef);
    protected abstract long extractId(T row);

    protected long preCallPauseMs() { return 100L; }

    public int ingest(String accountRef) {
        long startMs = ckpt.get("binance", accountRef, kind()).map(Instant::toEpochMilli).orElse(startTimeMs());
        startMs = Math.max(startMs, TRADE_HISTORY_START);//not before earliest possible start date

        Long fromId = null;
        int inserted = 0;

        for (int page = 0; page < 10_000; page++) {
            var p = new LinkedHashMap<String,Object>();
            p.put("symbol", symbol());
            p.put("limit", 1000);
            if (fromId != null) p.put("fromId", fromId);
            else if (startMs > 0) p.put("startTime", startMs);

            RateLimit.beforeCall(preCallPauseMs());
            List<T> rows = client.get(path(), p, listOfT());

            if (rows == null || rows.isEmpty()) break;

            long maxTs = startMs;
            long maxId = -1;
            for (T r : rows) {
                RowResult rr = handleRow(r, accountRef);
                inserted += rr.inserted();
                if (rr.eventTsMillis() > 0) maxTs = Math.max(maxTs, rr.eventTsMillis());
                long id = extractId(r);
                if (id >= 0) maxId = Math.max(maxId, id);
            }
            if (maxTs > startMs) {
                ckpt.put("binance", accountRef, kind(), Instant.ofEpochMilli(maxTs), null);
                startMs = maxTs;
            }
            if (rows.size() < 1000 || maxId < 0) break;
            fromId = maxId + 1;
        }
        return inserted;
    }

    // ******** FIX: provide a ParameterizedTypeReference<List<T>> ********
    protected ParameterizedTypeReference<List<T>> listOfT() {
        return new ParameterizedTypeReference<List<T>>() {};
    }
}
