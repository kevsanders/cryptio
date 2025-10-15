package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxUpserter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BinanceConvertIngestService {
    private static final ParameterizedTypeReference<Map<String,Object>> MAP_OF_STRING_OBJECT =
            new ParameterizedTypeReference<>() {};

    // try shorter windows to reduce weight spikes
    private static final long WINDOW_MS = java.time.Duration.ofDays(45).toMillis();
    public static final long TRADE_HISTORY_START = LocalDate.of(2017, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

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

    public int ingest(String accountRef, Instant sinceInclusive) {
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli()
                : ckpt.get("binance", accountRef, "convert").map(Instant::toEpochMilli).orElse(0L);
        startMs = Math.max(startMs, TRADE_HISTORY_START);//not before earliest possible start date

        int inserted = 0;
        long pageStart = startMs;
        final long now = System.currentTimeMillis();

        for (int page = 0; page < 500 && pageStart <= now; page++) {
            long endMs = Math.min(now, pageStart + WINDOW_MS);

            var p = new LinkedHashMap<String,Object>();
            p.put("startTime", pageStart);
            p.put("endTime", endMs);
            // If supported by the endpoint; if not, remove
            p.put("limit", 1000);

            //binance limits to 1 request every 2 seconds
            log.info("pausing before making request {}", LocalDateTime.ofInstant(Instant.ofEpochMilli(pageStart), ZoneOffset.UTC));
            sleepQuietly(2000);
            Map<String, Object> root = client.get("/sapi/v1/convert/tradeFlow", p, MAP_OF_STRING_OBJECT);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) root.getOrDefault("rows", List.of());
            if (rows.isEmpty()) {
                pageStart = endMs + 1; // next window
                sleepQuietly(250);
                continue;
            }
            log.info("saving {} rows of converts", rows.size());

            long maxTs = pageStart;
            for (var r : rows) {
                String orderId = String.valueOf(r.get("orderId"));
                long createTime = ((Number) r.get("createTime")).longValue();
                String fromAsset = String.valueOf(r.get("fromAsset"));
                String toAsset   = String.valueOf(r.get("toAsset"));
                BigDecimal fromAmount = new BigDecimal(String.valueOf(r.get("fromAmount")));
                BigDecimal toAmount   = new BigDecimal(String.valueOf(r.get("toAmount")));
                Instant ts = Instant.ofEpochMilli(createTime);

                tx.upsert("binance", accountRef, fromAsset, "N/A", "CONVERT_OUT",
                        fromAmount, null, null, null, ts, "convert:out:" + orderId);
                tx.upsert("binance", accountRef, toAsset, "N/A", "CONVERT_IN",
                        toAmount, null, null, null, ts, "convert:in:" + orderId);

                inserted += 2;
                if (createTime > maxTs) maxTs = createTime;
            }

            ckpt.put("binance", accountRef, "convert", Instant.ofEpochMilli(maxTs), null);

            // progress to next page/window
            pageStart = (maxTs <= pageStart) ? endMs + 1 : maxTs + 1;

            // small pacing between pages (still useful even with client-level retry)
            sleepQuietly(200);
        }
        return inserted;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
