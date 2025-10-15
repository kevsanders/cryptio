package com.sandkev.cryptio.exchange.binance;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
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

    /** Run all three using the same 'since' (or per-service checkpoint when null). */
    public int ingestAll(String accountRef, @Nullable Instant sinceInclusive) {
        return ingestAllDetailed(accountRef, sinceInclusive).total();
    }

    /** Same as ingestAll but returns a breakdown. */
    public Result ingestAllDetailed(String accountRef, @Nullable Instant sinceInclusive) {
        log.info("Binance ingest-all start accountRef={}, since={}", accountRef, sinceInclusive);

        int dustCount = 0, convertCount = 0, rewardsCount = 0;

        // Run dust (isolated)
        try {
            dustCount = ingestDust(accountRef, sinceInclusive);
            log.info("Binance dust ingested: {}", dustCount);
        } catch (Exception e) {
            log.warn("Binance dust ingest failed: {}", e.getMessage(), e);
        }

        // tiny pacing to reduce 429 bursts (optional)
        sleepQuietly(200);

        // Run convert (isolated)
        try {
            convertCount = ingestConvertTrades(accountRef, sinceInclusive);
            log.info("Binance convert ingested: {}", convertCount);
        } catch (Exception e) {
            log.warn("Binance convert ingest failed: {}", e.getMessage(), e);
        }

        sleepQuietly(200);

        // Run rewards (isolated)
        try {
            rewardsCount = ingestRewards(accountRef, sinceInclusive);
            log.info("Binance rewards ingested: {}", rewardsCount);
        } catch (Exception e) {
            log.warn("Binance rewards ingest failed: {}", e.getMessage(), e);
        }

        var result = new Result(dustCount, convertCount, rewardsCount);
        log.info("Binance ingest-all done accountRef={}, total={}, breakdown={}", accountRef, result.total(), result);
        return result;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @Value
    public static class Result {
        int dust;
        int convert;
        int rewards;
        public int total() { return dust + convert + rewards; }
    }
}
