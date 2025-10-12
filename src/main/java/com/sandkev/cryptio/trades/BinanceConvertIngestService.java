package com.sandkev.cryptio.trades;

// src/main/java/com/sandkev/cryptio/binance/BinanceConvertIngestService.java
//package com.sandkev.cryptio.binance;

import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.spot.BinanceSignedClient;
import com.sandkev.cryptio.tx.TxUpserter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BinanceConvertIngestService {

    private final BinanceSignedClient client;
    private final IngestCheckpointDao ckpt;
    private final TxUpserter tx;

    public BinanceConvertIngestService(BinanceSignedClient client, IngestCheckpointDao ckpt, TxUpserter tx) {
        this.client = client; this.ckpt = ckpt; this.tx = tx;
    }

    public int ingest(String accountRef, Instant sinceInclusive) {
        //final long startMs = effectiveStartMs("convert", sinceInclusive);
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli() :
                ckpt.get("binance", accountRef, "convert").map(Instant::toEpochMilli).orElse(0L);

        int inserted = 0;
        long pageStart = startMs;
        for (int page=0; page<200; page++) {
            var p = new LinkedHashMap<String,String>();
            p.put("startTime", String.valueOf(pageStart));
            p.put("endTime", String.valueOf(System.currentTimeMillis()));
            String path = client.signedGetPath("/sapi/v1/convert/tradeFlow", p);

            @SuppressWarnings("unchecked")
            Map<String,Object> resp = client.getJson(path, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String,Object>> rows = (List<Map<String,Object>>) resp.getOrDefault("rows", List.of());
            if (rows == null || rows.isEmpty()) break;

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
                maxTs = Math.max(maxTs, createTime);
            }

            //ckpt.saveSince("binance"+".convert", maxTs + 1);
            ckpt.put("binance", accountRef, "convert", Instant.ofEpochMilli(maxTs), null);

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

