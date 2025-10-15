package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.balance.BinanceSignedClientImpl;
import com.sandkev.cryptio.tx.TxUpserter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sandkev.cryptio.exchange.binance.BinanceDustIngestService.MAP_OF_STRING_OBJECT;

@Service
public class BinanceRewardsIngestService {

    private final BinanceSignedClient client;
    private final IngestCheckpointDao ckpt;
    private final TxUpserter tx;

    public BinanceRewardsIngestService(BinanceSignedClient client, IngestCheckpointDao ckpt, TxUpserter tx) {
        this.client = client; this.ckpt = ckpt; this.tx = tx;
    }

    public int ingest(String accountRef, Instant sinceInclusive) {
        //final long startMs = effectiveStartMs("rewards", sinceInclusive);
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli() :
                ckpt.get("binance", accountRef, "rewards").map(Instant::toEpochMilli).orElse(0L);

        int inserted = 0;
        long pageStart = startMs;
        for (int page=0; page<200; page++) {
            // Binance caps limit at 500 and window at 180 days
            long endMs = Math.min(System.currentTimeMillis(), pageStart + 180L*24*60*60*1000);
            var p = new LinkedHashMap<String,Object>();
            p.put("startTime", String.valueOf(pageStart));
            p.put("endTime", String.valueOf(endMs));
            p.put("limit", "500");


            Map<String, Object> root = client.get("/sapi/v1/asset/assetDividend", p, MAP_OF_STRING_OBJECT);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) root.getOrDefault("rows", List.of());
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

            //ckpt.saveSince("binance"+".rewards", Instant.ofEpochMilli(maxTs).toEpochMilli() + 1);
            ckpt.put("binance", accountRef, "rewards", Instant.ofEpochMilli(maxTs), null);
            if (maxTs <= pageStart) break;
            pageStart = maxTs + 1;
        }
        return inserted;
    }
    private long effectiveStartMs(String kind, Instant sinceInclusive) {
        long ck = ckpt.getSince("binance"+ "." + kind).orElse(0L);
        long si = sinceInclusive != null ? sinceInclusive.toEpochMilli() : 0L;
        long start = Math.max(ck, si);
        if (start == 0L) start = System.currentTimeMillis() - Duration.ofDays(90).toMillis() + 1;
        return start;
        // (If you want “prefer since unless checkpoint is newer”, this already does it.)
    }

}
