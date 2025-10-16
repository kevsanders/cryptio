// src/main/java/com/sandkev/cryptio/exchange/binance/BinanceTradeIngestService.java
package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.portfolio.AssetUniverseDao;
import com.sandkev.cryptio.tx.TxUpserter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceTradeIngestService {

    private final BinanceSignedClient client;
    private final TxUpserter tx;
    private final IngestCheckpointDao ckpt;
    private final AssetUniverseDao assetsDao;
    private final BinanceSymbolMapper symbolMapper; // your existing one

    public int ingestAllAssets(String accountRef, @Nullable Instant sinceInclusive) {
        Set<String> assets = assetsDao.assetsForAccount("binance", accountRef);
        List<String> symbols = new ArrayList<>();
        for (String a : assets) {
            String mkt = symbolMapper.toMarket(a);
            if (mkt != null && !mkt.isBlank()) symbols.add(mkt);
        }
        int total = 0;
        long seed = sinceInclusive == null ? 0L : sinceInclusive.toEpochMilli();
        for (String sym : symbols) {
            try {
                total += new BinanceSymbolTradesIngest(client, ckpt, tx, sym, seed).ingest(accountRef);
            } catch (RuntimeException ex) {
                String msg = ex.getMessage();
                if (msg != null && msg.contains("\"code\":-1121")) {
                    log.warn("Skipping invalid symbol {}", sym);
                    continue;
                }
                log.error("Trade ingest failed for {}: {}", sym, msg, ex);
            }
        }
        return total;
    }
}
