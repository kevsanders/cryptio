package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxUpserter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class BinanceRewardsIngestService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_OF_STRING_OBJECT =
            new ParameterizedTypeReference<>() {};

    private static final long NINETY_DAYS_MS = Duration.ofDays(90).toMillis();

    private final BinanceSignedClient client;
    private final IngestCheckpointDao ckpt;
    private final TxUpserter tx;

    public BinanceRewardsIngestService(BinanceSignedClient client,
                                       IngestCheckpointDao ckpt,
                                       TxUpserter tx) {
        this.client = client;
        this.ckpt = ckpt;
        this.tx = tx;
    }

    /** Ingest asset dividends (staking/earn “rewards”). */
    public int ingest(String accountRef, Instant sinceInclusive) {
        long startMs = (sinceInclusive != null)
                ? sinceInclusive.toEpochMilli()
                : ckpt.get("binance", accountRef, "rewards").map(Instant::toEpochMilli).orElse(0L);

        int inserted = 0;
        long windowStart = startMs;
        final long now = System.currentTimeMillis();

        for (int page = 0; page < 500 && windowStart <= now; page++) {

            long windowEnd = Math.min(windowStart + NINETY_DAYS_MS - 1, now);

            var params = new LinkedHashMap<String, Object>();
            params.put("startTime", windowStart);
            params.put("endTime", windowEnd);
            params.put("limit", 500);

            Map<String, Object> root = client.get("/sapi/v1/asset/assetDividend", params, MAP_OF_STRING_OBJECT);
            if (root == null) {
                // Advance window and continue; treat as no data in this window
                windowStart = windowEnd + 1;
                continue;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) root.getOrDefault("rows", List.of());

            if (rows.isEmpty()) {
                // No data in this window, advance to next
                windowStart = windowEnd + 1;
                continue;
            }
            log.info("saving {} rows of rewards", rows.size());

            long maxTs = windowStart;

            for (var r : rows) {
                String asset = String.valueOf(r.get("asset"));
                BigDecimal amount = new BigDecimal(String.valueOf(r.getOrDefault("amount", "0")));
                long divTime = ((Number) r.get("divTime")).longValue();
                String tranId = (r.get("tranId") != null) ? String.valueOf(r.get("tranId"))
                               : (r.get("id") != null) ? String.valueOf(r.get("id"))
                               : asset + ":" + divTime; // last-resort stable key

                tx.upsert(
                    "binance",
                    accountRef,
                    asset,
                    "N/A",
                    "REWARD",
                    amount,
                    null,              // price
                    BigDecimal.ZERO,   // fee
                    null,              // fee asset
                    Instant.ofEpochMilli(divTime),
                    "reward:" + asset + ":" + tranId
                );

                if (divTime > maxTs) maxTs = divTime;
                inserted++;
            }

            // checkpoint at max seen in this window
            ckpt.put("binance", accountRef, "rewards", Instant.ofEpochMilli(maxTs), null);

            // Progress: if nothing advanced, hop past window; else continue from last seen +1
            windowStart = (maxTs <= windowStart) ? (windowEnd + 1) : (maxTs + 1);
        }

        return inserted;
    }
}
