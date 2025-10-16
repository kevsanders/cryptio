package com.sandkev.cryptio.portfolio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"test","h2"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Disabled
class PortfolioBalanceE2ETest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PortfolioValuationService valuation;

    private Long exchangeId;
    private Long accountId;
    private Long btcId;
    private Long ethId;

    @BeforeEach
    void seed() {
        // Clean for determinism
        jdbc.update("delete from balance_snapshot");
        jdbc.update("delete from asset_alias");
        jdbc.update("delete from asset");
        jdbc.update("delete from exchange_account");

        // 1) exchange 'binance' should exist from migrations; fetch id
        exchangeId = jdbc.queryForObject(
                "select id from exchange where code='binance'",
                Long.class
        );

        // 2) ensure account 'primary' on binance
        jdbc.update("""
            merge into exchange_account (exchange_id, account_ref, display_name, created_at)
            key (exchange_id, account_ref)
            values (?, ?, ?, current_timestamp)
        """, exchangeId, "primary", "primary");
        accountId = jdbc.queryForObject(
                "select id from exchange_account where exchange_id=? and account_ref=?",
                Long.class, exchangeId, "primary"
        );

        // 3) ensure assets BTC & ETH exist
        jdbc.update("""
            merge into asset (symbol, chain, contract, decimals)
            key (symbol, chain, contract)
            values ('BTC', '', '', 8)
        """);
        btcId = jdbc.queryForObject(
                "select id from asset where symbol='BTC' and chain='' and contract=''", Long.class
        );

        jdbc.update("""
            merge into asset (symbol, chain, contract, decimals)
            key (symbol, chain, contract)
            values ('ETH', '', '', 18)
        """);
        ethId = jdbc.queryForObject(
                "select id from asset where symbol='ETH' and chain='' and contract=''", Long.class
        );
    }

    @Test
    void latestHoldings_reflectsMostRecentSnapshots_perAsset() {
        // Given two snapshots per asset with increasing as_of
        Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2024-01-02T00:00:00Z");

        // BTC: first 0.10 then 0.10862355
        insertSnapshot(accountId, btcId, "BTC", "0.10",  "0", Timestamp.from(t0));
        insertSnapshot(accountId, btcId, "BTC", "0.10862355", "0", Timestamp.from(t1));

        // ETH: first 1.5 then 1.75
        insertSnapshot(accountId, ethId, "ETH", "1.50", "0", Timestamp.from(t0));
        insertSnapshot(accountId, ethId, "ETH", "1.75", "0", Timestamp.from(t1));

        // When
        var holdings = valuation.loadLatestBalances("primary");

        // Then: we should see the latest qtys
        var btc = holdings.stream().filter(h -> "BTC".equals(h.asset())).findFirst();
        var eth = holdings.stream().filter(h -> "ETH".equals(h.asset())).findFirst();
        assertThat(btc).isPresent();
        assertThat(eth).isPresent();

        assertThat(btc.get().qty()).isEqualByComparingTo("0.10862355");
        assertThat(eth.get().qty()).isEqualByComparingTo("1.75");
    }

    @Test
    void mergeKey_isIdempotent_onSameAsOf() {
        // Given same (account, asset, as_of) repeated insert (merge)
        Timestamp asOf = Timestamp.from(Instant.parse("2024-02-01T12:00:00Z"));

        insertSnapshot(accountId, btcId, "BTC", "0.5", "0", asOf);
        insertSnapshot(accountId, btcId, "BTC", "0.5", "0", asOf); // same key again

        // Then: only one row for that key
        Integer rows = jdbc.queryForObject("""
            select count(*) from balance_snapshot
            where exchange_account_id=? and asset_id=? and as_of=?
        """, Integer.class, accountId, btcId, asOf);
        assertThat(rows).isEqualTo(1);

        // And service still sees it
        var holdings = valuation.loadLatestBalances("primary");
        var btc = holdings.stream().filter(h -> "BTC".equals(h.asset())).findFirst();
        assertThat(btc).isPresent();
        assertThat(btc.get().qty()).isEqualByComparingTo("0.5");
    }

    @Test
    void laterSnapshot_overridesEarlierInLatestView_onlyForThatAsset() {
        // Given BTC has older and newer, ETH only older
        Instant btcOld = Instant.parse("2024-03-10T00:00:00Z");
        Instant btcNew = Instant.parse("2024-03-11T00:00:00Z");
        Instant ethOnly = Instant.parse("2024-03-10T00:00:00Z");

        insertSnapshot(accountId, btcId, "BTC", "0.25", "0", Timestamp.from(btcOld));
        insertSnapshot(accountId, btcId, "BTC", "0.30", "0", Timestamp.from(btcNew));

        insertSnapshot(accountId, ethId, "ETH", "2.00", "0", Timestamp.from(ethOnly));

        var holdings = valuation.loadLatestBalances("primary");

        var btc = holdings.stream().filter(h -> "BTC".equals(h.asset())).findFirst().orElseThrow();
        var eth = holdings.stream().filter(h -> "ETH".equals(h.asset())).findFirst().orElseThrow();

        assertThat(btc.qty()).isEqualByComparingTo("0.30"); // newer overrides
        assertThat(eth.qty()).isEqualByComparingTo("2.00"); // only one snapshot
    }

    // --- helpers ---

    private void insertSnapshot(Long exchangeAccountId,
                                Long assetId,
                                String exchangeSymbol,
                                String free,
                                String locked,
                                Timestamp asOf) {
        jdbc.update("""
            merge into balance_snapshot (exchange_account_id, asset_id, exchange_symbol, free_amt, locked_amt, as_of, ingest_id, created_at)
            key (exchange_account_id, asset_id, as_of)
            values (?, ?, ?, ?, ?, ?, null, current_timestamp)
        """,
                exchangeAccountId, assetId, exchangeSymbol,
                new BigDecimal(free), new BigDecimal(locked), asOf
        );
    }
}
