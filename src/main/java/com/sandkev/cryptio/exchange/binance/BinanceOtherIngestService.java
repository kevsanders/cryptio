// src/main/java/com/sandkev/cryptio/binance/BinanceOtherIngestService.java
package com.sandkev.cryptio.exchange.binance;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Facade over specific Binance ingestion services.
 * Keeps backward compatibility with existing callers that referenced
 * BinanceOtherIngestService while the real logic lives in small, focused classes.
 */
@Service
public class BinanceOtherIngestService {

    private final BinanceDustIngestService dust;
    private final BinanceConvertIngestService convert;
    private final BinanceRewardsIngestService rewards;

    public BinanceOtherIngestService(BinanceDustIngestService dust,
                                     BinanceConvertIngestService convert,
                                     BinanceRewardsIngestService rewards) {
        this.dust = dust;
        this.convert = convert;
        this.rewards = rewards;
    }

    /** Delegate: Dust conversions to BNB (dribblet). */
    public int ingestDust(String accountRef, @Nullable Instant sinceInclusive) {
        return dust.ingest(accountRef, sinceInclusive);
    }

    /** Delegate: Convert tradeFlow (asset ↔ asset). */
    public int ingestConvertTrades(String accountRef, @Nullable Instant sinceInclusive) {
        return convert.ingest(accountRef, sinceInclusive);
    }

    /** Delegate: Rewards / dividends / airdrops. */
    public int ingestRewards(String accountRef, @Nullable Instant sinceInclusive) {
        return rewards.ingest(accountRef, sinceInclusive);
    }

    /** Convenience: run all three using the same 'since' (or checkpoint when null). */
    public int ingestAll(String accountRef, @Nullable Instant sinceInclusive) {
        int n = 0;
        n += ingestDust(accountRef, sinceInclusive);
        n += ingestConvertTrades(accountRef, sinceInclusive);
        n += ingestRewards(accountRef, sinceInclusive);
        return n;
    }
}
