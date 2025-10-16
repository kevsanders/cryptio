package com.sandkev.cryptio.exchange.binance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Thin orchestrator so web/controller code never depends on individual ingesters.
 * Returns a summary Result with counts per category (useful for flash messages/logs).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceCompositeIngestService {

    private final BinanceTradeIngestService trades;
    private final BinanceDepositsIngestService deposits;
    private final BinanceWithdrawalsIngestService withdrawals;
    private final BinanceConvertIngestService converts;
    private final BinanceDustIngestService dust;
    private final BinanceRewardsIngestService rewards;

    public record Result(
            int trades,
            int deposits,
            int withdrawals,
            int converts,
            int dust,
            int rewards
    ) {
        public int total() { return trades + deposits + withdrawals + converts + dust + rewards; }
    }

    /** Run everything (safe to call repeatedly; all ingesters are idempotent via external_id + checkpoints). */
    public Result ingestAll(String accountRef, @Nullable Instant sinceInclusive) {
        int t  = safeRun(() -> trades.ingestAllAssets(accountRef, sinceInclusive), "trades");
        int d  = safeRun(() -> deposits.ingest(accountRef, sinceInclusive),       "deposits");
        int w  = safeRun(() -> withdrawals.ingest(accountRef, sinceInclusive),    "withdrawals");
        int c  = safeRun(() -> converts.ingest(accountRef, sinceInclusive),       "converts");
        int du = safeRun(() -> dust.ingest(accountRef, sinceInclusive),           "dust");
        int r  = safeRun(() -> rewards.ingest(accountRef, sinceInclusive),        "rewards");
        return new Result(t, d, w, c, du, r);
    }

    public int ingestTrades(String accountRef, @Nullable Instant sinceInclusive)      { return trades.ingestAllAssets(accountRef, sinceInclusive); }
    public int ingestDeposits(String accountRef, @Nullable Instant sinceInclusive)    { return deposits.ingest(accountRef, sinceInclusive); }
    public int ingestWithdrawals(String accountRef, @Nullable Instant sinceInclusive) { return withdrawals.ingest(accountRef, sinceInclusive); }
    public int ingestConverts(String accountRef, @Nullable Instant sinceInclusive)    { return converts.ingest(accountRef, sinceInclusive); }
    public int ingestDust(String accountRef, @Nullable Instant sinceInclusive)        { return dust.ingest(accountRef, sinceInclusive); }
    public int ingestRewards(String accountRef, @Nullable Instant sinceInclusive)     { return rewards.ingest(accountRef, sinceInclusive); }

    /** Keep dashboard UX resilient — log and continue if one source fails. */
    private int safeRun(Job job, String label) {
        try { return job.run(); }
        catch (RuntimeException e) {
            log.warn("Composite ingest '{}' failed: {}", label, e.toString(), e);
            return 0;
        }
    }
    @FunctionalInterface private interface Job { int run(); }
}
