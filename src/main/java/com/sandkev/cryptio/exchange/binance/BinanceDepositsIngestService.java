// src/main/java/com/sandkev/cryptio/exchange/binance/BinanceDepositsIngestService.java
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
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BinanceDepositsIngestService extends TimeWindowIngest<Map<String,Object>> {
    private static final ParameterizedTypeReference<List<Map<String,Object>>> LIST = new ParameterizedTypeReference<>() {};
    public BinanceDepositsIngestService(BinanceSignedClient client, IngestCheckpointDao ckpt, TxUpserter tx) {
        super(client, ckpt, tx);
    }
    @Override protected String kind() { return "deposits"; }
    @Override protected String path() { return "/sapi/v1/capital/deposit/hisrec"; }
    @Override protected void addConstantParams(Map<String,Object> p) { p.put("limit", 1000); }

    @Override protected List<Map<String, Object>> fetch(long startMs, long endMs) {
        return call(baseParams(startMs, endMs), LIST);
    }

    @Override protected RowResult handleRow(Map<String,Object> r, String accountRef) {
        String coin = String.valueOf(r.get("coin"));
        BigDecimal amt = new BigDecimal(String.valueOf(r.get("amount")));
        long insertTime = ((Number) r.get("insertTime")).longValue();
        String txId = String.valueOf(r.get("txId"));
        int inserted = tx.upsert("binance", accountRef, coin, "N/A", "DEPOSIT",
                amt, null, BigDecimal.ZERO, coin,
                Instant.ofEpochMilli(insertTime),
                "deposit:"+coin+":"+txId+":"+insertTime);
        return RowResult.many(inserted, insertTime);
    }
}
