// src/main/java/com/sandkev/cryptio/exchange/binance/BinanceConvertIngestService.java
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BinanceConvertIngestService extends TimeWindowIngest<Map<String,Object>> {

    private static final ParameterizedTypeReference<Map<String,Object>> MAP = new ParameterizedTypeReference<>() {};
    public BinanceConvertIngestService(BinanceSignedClient client, IngestCheckpointDao ckpt, TxUpserter tx) {
        super(client, ckpt, tx);
    }

    @Override protected String kind() { return "convert"; }
    @Override protected String path() { return "/sapi/v1/convert/tradeFlow"; }
    @Override protected Duration windowSize() { return Duration.ofDays(45); }

    @Override protected void addConstantParams(Map<String,Object> p) { p.put("limit", 1000); }

    protected long preCallPauseMs() { return 2000; }

    @Override protected List<Map<String, Object>> fetch(long startMs, long endMs) {
        Map<String,Object> root = call(baseParams(startMs, endMs), MAP);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> rows = (List<Map<String,Object>>) root.getOrDefault("rows", List.of());
        return rows;
    }

    @Override protected RowResult handleRow(Map<String,Object> r, String accountRef) {
        String orderId = String.valueOf(r.get("orderId"));
        long createTime = ((Number) r.get("createTime")).longValue();
        String fromAsset = String.valueOf(r.get("fromAsset"));
        String toAsset   = String.valueOf(r.get("toAsset"));
        BigDecimal fromAmount = new BigDecimal(String.valueOf(r.get("fromAmount")));
        BigDecimal toAmount   = new BigDecimal(String.valueOf(r.get("toAmount")));

        var ts = Instant.ofEpochMilli(createTime);
        int inserted = 0;
        inserted += tx.upsert("binance", accountRef, fromAsset, "N/A", "CONVERT_OUT", fromAmount, null, null, null, ts, "convert:out:"+orderId);
        inserted += tx.upsert("binance", accountRef, toAsset,   "N/A", "CONVERT_IN",  toAmount,   null, null, null, ts, "convert:in:"+orderId);

        return RowResult.many(inserted, createTime);
    }
}
