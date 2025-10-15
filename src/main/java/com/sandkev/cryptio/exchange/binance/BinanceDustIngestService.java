// src/main/java/com/sandkev/cryptio/binance/BinanceDustIngestService.java
package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClientImpl;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxUpserter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BinanceDustIngestService {

    static final ParameterizedTypeReference<Map<String,Object>> MAP_OF_STRING_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final BinanceSignedClientImpl client;
    private final IngestCheckpointDao ckpt;
    private final TxUpserter tx;

    public BinanceDustIngestService(BinanceSignedClientImpl client, IngestCheckpointDao ckpt, TxUpserter tx) {
        this.client = client; this.ckpt = ckpt; this.tx = tx;
    }

    public int ingest(String accountRef, Instant sinceInclusive) {
        long startMs = sinceInclusive != null ? sinceInclusive.toEpochMilli()
                : ckpt.get("binance", accountRef, "dust").map(Instant::toEpochMilli).orElse(0L);

        int inserted = 0;
        long pageStart = startMs;

        for (int page = 0; page < 50; page++) {
            var p = new LinkedHashMap<String, Object>();
            p.put("startTime", String.valueOf(pageStart));
            p.put("endTime", String.valueOf(System.currentTimeMillis()));

            // Top-level OBJECT, not array
            Map<String, Object> root = client.get("/sapi/v1/asset/dribblet", p, MAP_OF_STRING_OBJECT);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dribs = (List<Map<String, Object>>) root.getOrDefault("userAssetDribblets", List.of());
            if (dribs.isEmpty()) break;

            long maxTs = pageStart;
            for (var d : dribs) {
                long operateTime = asLong(d.get("operateTime"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items =
                        (List<Map<String, Object>>) d.getOrDefault("userAssetDribbletDetails", List.of());

                for (var it : items) {
                    String transId = String.valueOf(it.get("transId"));
                    String fromAsset = String.valueOf(it.get("fromAsset"));
                    BigDecimal amount = asBigDec(it.get("amount"));
                    BigDecimal bnbReceived = asBigDec(it.get("transferedAmount"));      // Binance typo: "transferedAmount"
                    BigDecimal bnbFee = asBigDec(it.get("serviceChargeAmount"));

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

    /* --- helpers --- */
    private static long asLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }
    private static BigDecimal asBigDec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
    }
}
