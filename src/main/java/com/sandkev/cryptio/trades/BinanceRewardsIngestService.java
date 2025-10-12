package com.sandkev.cryptio.trades;

// src/main/java/com/sandkev/cryptio/binance/BinanceRewardsIngestService.java
//package com.sandkev.cryptio.binance;

import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.spot.BinanceSignedClient;
import com.sandkev.cryptio.tx.TxWriter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BinanceRewardsIngestService {

    private final BinanceSignedClient client;
    private final IngestCheckpointDao ckpt;
    private final TxWriter tx;

    public BinanceRewardsIngestService(BinanceSignedClient client, IngestCheckpointDao ckpt, TxWriter tx) {
        this.client = client; this.ckpt = ckpt; this.tx = tx;
    }

    public int ingest(String accountRef, Instant sinceInclusive) {
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli() :
                ckpt.get("binance", accountRef, "rewards").map(Instant::toEpochMilli).orElse(0L);

        int inserted = 0;
        long pageStart = startMs;
        for (int page=0; page<200; page++) {
            // Binance caps limit at 500 and window at 180 days
            long endMs = Math.min(System.currentTimeMillis(), pageStart + 180L*24*60*60*1000);
            var p = new LinkedHashMap<String,String>();
            p.put("startTime", String.valueOf(pageStart));
            p.put("endTime", String.valueOf(endMs));
            p.put("limit", "500");

            String path = client.signedGetPath("/sapi/v1/asset/assetDividend", p);

            @SuppressWarnings("unchecked")
            Map<String,Object> resp = client.getJson(path, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String,Object>> rows = (List<Map<String,Object>>) resp.getOrDefault("rows", List.of());
            if (rows.isEmpty()) break;

            long maxTs = pageStart;
            for (var r : rows) {
                String asset = String.valueOf(r.get("asset"));
                BigDecimal amount = new BigDecimal(String.valueOf(r.get("amount")));
                long divTime = ((Number) r.get("divTime")).longValue();
                String tranId = String.valueOf(r.get("tranId"));
                Instant ts = Instant.ofEpochMilli(divTime);

                tx.upsert("binance", accountRef, asset, "N/A", "REWARD",
                        amount, null, null, null, ts, "reward:" + asset + ":" + tranId);

                inserted++;
                maxTs = Math.max(maxTs, divTime);
            }

            ckpt.put("binance", accountRef, "rewards", Instant.ofEpochMilli(maxTs), null);
            if (maxTs <= pageStart) break;
            pageStart = maxTs + 1;
        }
        return inserted;
    }
}
