package com.sandkev.cryptio.trades;

// src/main/java/com/sandkev/cryptio/binance/BinanceDustIngestService.java
//package com.sandkev.cryptio.binance;

import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxWriter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BinanceDustIngestService {

    private final BinanceSignedClient client;
    private final IngestCheckpointDao ckpt;
    private final TxWriter tx;

    public BinanceDustIngestService(BinanceSignedClient client, IngestCheckpointDao ckpt, TxWriter tx) {
        this.client = client; this.ckpt = ckpt; this.tx = tx;
    }

    public int ingest(String accountRef, Instant sinceInclusive) {
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli() :
                ckpt.get("binance", accountRef, "dust").map(Instant::toEpochMilli).orElse(0L);

        int inserted = 0;
        long pageStart = startMs;
        for (int page=0; page<50; page++) {
            var p = new LinkedHashMap<String,String>();
            p.put("startTime", String.valueOf(pageStart));
            p.put("endTime", String.valueOf(System.currentTimeMillis()));
            String path = client.signedGetPath("/sapi/v1/asset/dribblet", p);

            @SuppressWarnings("unchecked")
            Map<String,Object> resp = client.getJson(path, Map.class, "Binance dribblet error");

            List<Map<String,Object>> dribs = (List<Map<String,Object>>) resp.getOrDefault("userAssetDribblets", List.of());
            if (dribs.isEmpty()) break;

            long maxTs = pageStart;
            for (var d : dribs) {
                long operateTime = ((Number)d.get("operateTime")).longValue();
                @SuppressWarnings("unchecked")
                List<Map<String,Object>> items =
                        (List<Map<String,Object>>) d.getOrDefault("userAssetDribbletDetails", List.of());
                for (var it : items) {
                    String transId = String.valueOf(it.get("transId"));
                    String fromAsset = String.valueOf(it.get("fromAsset"));
                    BigDecimal amount = new BigDecimal(String.valueOf(it.getOrDefault("amount","0")));
                    BigDecimal bnbReceived = new BigDecimal(String.valueOf(it.getOrDefault("transferedAmount","0")));
                    BigDecimal bnbFee = new BigDecimal(String.valueOf(it.getOrDefault("serviceChargeAmount","0")));

                    Instant ts = Instant.ofEpochMilli(operateTime);

                    // OUT: reduce fromAsset
                    tx.upsert("binance", accountRef, fromAsset, "N/A", "CONVERT_OUT",
                            amount, null, null, null, ts, "dust:out:" + fromAsset + ":" + transId);

                    // IN: increase BNB, fee in BNB
                    tx.upsert("binance", accountRef, "BNB", "N/A", "CONVERT_IN",
                            bnbReceived, null, bnbFee, "BNB", ts, "dust:in:BNB:" + transId);

                    inserted += 2;
                }
                maxTs = Math.max(maxTs, operateTime);
            }

            ckpt.put("binance", accountRef, "dust", Instant.ofEpochMilli(maxTs), null);
            if (maxTs <= pageStart) break;
            pageStart = maxTs + 1;
        }
        return inserted;
    }
}

