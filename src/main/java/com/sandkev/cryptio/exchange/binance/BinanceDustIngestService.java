// src/main/java/com/sandkev/cryptio/exchange/binance/BinanceDustIngestService.java
package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.exchange.binance.ingest.RowResult;
import com.sandkev.cryptio.exchange.binance.ingest.TimeWindowIngest;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxUpserter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BinanceDustIngestService extends TimeWindowIngest<Map<String,Object>> {

    private static final ParameterizedTypeReference<Map<String,Object>> MAP = new ParameterizedTypeReference<>() {};
    public BinanceDustIngestService(BinanceSignedClient client, IngestCheckpointDao ckpt, TxUpserter tx) {
        super(client, ckpt, tx);
    }

    @Override protected String kind() { return "dust"; }
    @Override protected String path() { return "/sapi/v1/asset/dribblet"; }

    @Override protected List<Map<String, Object>> fetch(long startMs, long endMs) {
        var p = baseParams(startMs, endMs);
        Map<String,Object> root = call(p, MAP);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> dribs = (List<Map<String,Object>>) root.getOrDefault("userAssetDribblets", List.of());
        // flatten to detail rows so handleRow can be simple
        List<Map<String,Object>> flat = new ArrayList<>();
        for (var d : dribs) {
            long operateTime = ((Number) d.get("operateTime")).longValue();
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> details = (List<Map<String,Object>>) d.getOrDefault("userAssetDribbletDetails", List.of());
            for (var it : details) {
                it.put("_opTime", operateTime);
                flat.add(it);
            }
        }
        return flat;
    }

    @Override protected RowResult handleRow(Map<String,Object> it, String accountRef) {
        long operateTime = ((Number) it.get("_opTime")).longValue();
        String transId   = String.valueOf(it.get("transId"));
        String fromAsset = String.valueOf(it.get("fromAsset"));
        BigDecimal amount      = asBigDec(it.get("amount"));
        BigDecimal bnbReceived = asBigDec(it.get("transferedAmount")); // sic
        BigDecimal bnbFee      = asBigDec(it.get("serviceChargeAmount"));

        var ts = Instant.ofEpochMilli(operateTime);

        int inserted =0;
        inserted += tx.upsert("binance", accountRef, fromAsset, "N/A", "CONVERT_OUT",
                amount, null, null, null, ts, "dust:out:"+fromAsset+":"+transId);
        inserted += tx.upsert("binance", accountRef, "BNB", "N/A", "CONVERT_IN",
                bnbReceived, null, bnbFee, "BNB", ts, "dust:in:BNB:"+transId);
        return RowResult.many(inserted, operateTime);
    }

    private static BigDecimal asBigDec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
    }
}
