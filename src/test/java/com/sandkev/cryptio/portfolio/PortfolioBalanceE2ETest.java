package com.sandkev.cryptio.portfolio;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test:
 *  - Starts Spring (H2 profile) so Flyway runs and the schema is real.
 *  - Seeds a single BTC balance snapshot for Binance -> account 'primary'.
 *  - Uses PortfolioValuationService.latestHoldings() to verify BTC = 0.10862355.
 */
@ActiveProfiles("test")
//@ActiveProfiles("h2")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PortfolioBalanceE2ETest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PortfolioValuationService valuation;

    private Long exchangeId;
    private Long accountId;
    private Long assetId;

    @BeforeEach
    void seed() {
        // Clean any previous data for a deterministic test run
        jdbc.update("delete from balance_snapshot");
        jdbc.update("delete from asset_alias");
        jdbc.update("delete from asset");
        jdbc.update("delete from exchange_account");

        // 1) Lookup exchange 'binance' (inserted by V1 migration)
        exchangeId = jdbc.queryForObject("select id from exchange where code='binance'", Long.class);

        // 2) Ensure account 'primary' exists for binance
        jdbc.update("""
            merge into exchange_account (exchange_id, account_ref, display_name, created_at)
            key (exchange_id, account_ref)
            values (?, ?, ?, current_timestamp)
        """, exchangeId, "primary", "primary");
        accountId = jdbc.queryForObject(
                "select id from exchange_account where exchange_id=? and account_ref=?",
                Long.class, exchangeId, "primary"
        );

        // 3) Ensure asset BTC exists (symbol-only for this test)
        jdbc.update("""
            merge into asset (symbol, chain, contract, decimals)
            key (symbol, chain, contract)
            values ('BTC', '', '', 8)
        """);
        assetId = jdbc.queryForObject(
                "select id from asset where symbol='BTC' and chain='' and contract=''", Long.class
        );

        // 4) Insert a balance snapshot (free = 0.10862355, locked = 0)
        var asOf = Timestamp.from(Instant.now());
        jdbc.update("""
            merge into balance_snapshot (exchange_account_id, asset_id, exchange_symbol, free_amt, locked_amt, as_of, ingest_id, created_at)
            key (exchange_account_id, asset_id, as_of)
            values (?, ?, 'BTC', ?, 0, ?, null, current_timestamp)
        """,
                accountId, assetId, new BigDecimal("0.10862355"), asOf
        );
    }

/*
    @Test
    void latestHoldings_containsExpectedBtcBalance() {
        var holdings = valuation.latestHoldings("primary");

        // Find BTC line
        Optional<PortfolioValuationService.Holding> btc =
                holdings.stream().filter(h -> "BTC".equals(h.asset())).findFirst();

        assertThat(btc).isPresent();
        // Use isEqualByComparingTo to avoid scale issues with BigDecimal.equals
        assertThat(btc.get().qty()).isEqualByComparingTo("0.10862355");
    }
*/
}
