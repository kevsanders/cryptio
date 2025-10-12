package com.sandkev.cryptio.spot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class BalanceIngestService {
    private final JdbcTemplate jdbc;
    private final BinanceSpotPositionsService binance;
    private final KrakenSpotPositionsService kraken;

    public BalanceIngestService(JdbcTemplate jdbc,
                                BinanceSpotPositionsService binance,
                                KrakenSpotPositionsService kraken) {
        this.jdbc = jdbc;
        this.binance = binance;
        this.kraken = kraken;
    }

    public void ingestBinance(String accountRef) {
        Map<String, BigDecimal> balances = binance.fetchSpotBalances(); // {BTC -> qty, ...}
        ingest("binance", accountRef, balances, /*lowercaseAlias*/ false);
    }

    public void ingestKraken(String accountRef) {
        Map<String, BigDecimal> balances = kraken.fetchSpotBalances(); // already normalized (BTC, ETH, USD, etc.)
        ingest("kraken", accountRef, balances, /*lowercaseAlias*/ true); // kraken aliases are often uppercase; we store lower
    }

    private void ingest(String exchangeCode, String accountRef, Map<String, BigDecimal> balances, boolean lowercaseAlias) {
        Long exchangeId = jdbc.queryForObject("select id from exchange where code = ?", Long.class, exchangeCode);

        // 1) ensure account (NO 'id' column in MERGE!)
        jdbc.update("""
        merge into exchange_account (exchange_id, account_ref, display_name, created_at)
        key (exchange_id, account_ref)
        values (?, ?, ?, current_timestamp)
        """,
                exchangeId, accountRef, accountRef
        );

        Long accountId = jdbc.queryForObject(
                "select id from exchange_account where exchange_id=? and account_ref=?",
                Long.class, exchangeId, accountRef
        );

        var asOf = java.sql.Timestamp.from(java.time.Instant.now());

        for (var e : balances.entrySet()) {
            String symbol = e.getKey();
            java.math.BigDecimal total = e.getValue();
            if (total == null || total.signum() == 0) continue;

            // 2) upsert canonical asset (NO 'id' column)
            jdbc.update("""
            merge into asset (symbol, chain, contract, decimals)
            key (symbol, chain, contract)
            values (?, '', '', 18)
            """, symbol
            );

            Long assetId = jdbc.queryForObject(
                    "select id from asset where symbol=? and chain='' and contract=''", Long.class, symbol
            );

            // 3) upsert alias (store lowercase to match unique constraint)
            String alias = lowercaseAlias ? symbol.toLowerCase(java.util.Locale.ROOT) : symbol;
            jdbc.update("""
            merge into asset_alias (exchange_id, alias, asset_id)
            key (exchange_id, alias)
            values (?, ?, ?)
            """, exchangeId, alias, assetId
            );

            // 4) write snapshot (NO 'id' column)
            // If you later split free/locked, change 0 to the appropriate value.
            jdbc.update("""
            merge into balance_snapshot (exchange_account_id, asset_id, exchange_symbol, free_amt, locked_amt, as_of, ingest_id, created_at)
            key (exchange_account_id, asset_id, as_of)
            values (?, ?, ?, ?, ?, ?, null, current_timestamp)
            """,
                    accountId, assetId, symbol, total, java.math.BigDecimal.ZERO, asOf
            );
        }
    }
}
