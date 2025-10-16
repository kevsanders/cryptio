// src/main/java/com/sandkev/cryptio/exchange/binance/BinanceRewardsIngestService.java
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
public class BinanceRewardsIngestService extends TimeWindowIngest<Map<String,Object>> {
    private static final ParameterizedTypeReference<Map<String,Object>> MAP = new ParameterizedTypeReference<>() {};
    public BinanceRewardsIngestService(BinanceSignedClient client, IngestCheckpointDao ckpt, TxUpserter tx) {
        super(client, ckpt, tx);
    }
    @Override protected String kind() { return "rewards"; }
    @Override protected String path() { return "/sapi/v1/asset/assetDividend"; }
    @Override protected Duration windowSize() { return Duration.ofDays(90); }
    @Override protected void addConstantParams(Map<String,Object> p) { p.put("limit", 500); }

    @Override protected List<Map<String, Object>> fetch(long startMs, long endMs) {
        Map<String,Object> root = call(baseParams(startMs, endMs), MAP);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> rows = (List<Map<String,Object>>) (root == null ? List.of() : root.getOrDefault("rows", List.of()));
        return rows;
    }

    @Override protected RowResult handleRow(Map<String,Object> r, String accountRef) {
        String asset = String.valueOf(r.get("asset"));
        BigDecimal amount = new BigDecimal(String.valueOf(r.getOrDefault("amount","0")));
        long divTime = ((Number) r.get("divTime")).longValue();
        String id = r.get("tranId") != null ? String.valueOf(r.get("tranId"))
                : r.get("id") != null ? String.valueOf(r.get("id"))
                : asset + ":" + divTime;

        int inserted =0;
        inserted += tx.upsert("binance", accountRef, asset, "N/A", "REWARD",
                amount, null, BigDecimal.ZERO, null,
                Instant.ofEpochMilli(divTime), "reward:"+asset+":"+id);
        return RowResult.many(inserted, divTime);
    }
}
