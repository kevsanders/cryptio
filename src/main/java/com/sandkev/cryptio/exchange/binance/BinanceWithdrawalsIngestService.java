// src/main/java/com/sandkev/cryptio/exchange/binance/BinanceWithdrawalsIngestService.java
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
public class BinanceWithdrawalsIngestService extends TimeWindowIngest<Map<String,Object>> {
    private static final ParameterizedTypeReference<List<Map<String,Object>>> LIST = new ParameterizedTypeReference<>() {};
    public BinanceWithdrawalsIngestService(BinanceSignedClient client, IngestCheckpointDao ckpt, TxUpserter tx) {
        super(client, ckpt, tx);
    }
    @Override protected String kind() { return "withdrawals"; }
    @Override protected String path() { return "/sapi/v1/capital/withdraw/history"; }
    @Override protected void addConstantParams(Map<String,Object> p) { p.put("limit", 1000); }

    @Override protected List<Map<String, Object>> fetch(long startMs, long endMs) {
        return call(baseParams(startMs, endMs), LIST);
    }

    @Override protected RowResult handleRow(Map<String,Object> r, String accountRef) {
        String id   = String.valueOf(r.get("id"));
        String coin = String.valueOf(r.get("coin"));
        BigDecimal amt = new BigDecimal(String.valueOf(r.get("amount")));
        BigDecimal fee = new BigDecimal(String.valueOf(r.getOrDefault("transactionFee","0")));
        long ts = parseTime(String.valueOf(r.getOrDefault("applyTime", String.valueOf(System.currentTimeMillis()))));

        int inserted =0;
        inserted += tx.upsert("binance", accountRef, coin, "N/A", "WITHDRAW",
                amt, null, fee, coin, Instant.ofEpochMilli(ts), "withdraw:"+id);
        return RowResult.many(inserted, ts);
    }

    private static long parseTime(String s) {
        try {
            var dt = java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return dt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            try { return Long.parseLong(s); } catch (Exception e) { return System.currentTimeMillis(); }
        }
    }
}
