package com.sandkev.cryptio.tx;

// src/main/java/com/sandkev/cryptio/tx/TxWriter.java
//package com.sandkev.cryptio.tx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

@Component
public class TxWriter {

    private final JdbcTemplate jdbc;

    public TxWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Idempotent upsert into tx using unique (exchange, external_id).
     * Keep 'id' auto-generated; never assign it.
     */
    public void upsert(
            String exchange, String accountRef,
            String base, String quote,
            String type,                   // e.g. BUY, SELL, DEPOSIT, WITHDRAW, CONVERT_IN, CONVERT_OUT, REWARD
            BigDecimal quantity,           // positive magnitude (we’ll sign in reconciliation)
            BigDecimal price,              // nullable
            BigDecimal fee, String feeAsset, // nullable
            Instant ts,
            String externalId
    ) {
        jdbc.update("""
            merge into tx (exchange, account_ref, base, quote, type, quantity, price, fee, fee_asset, ts, external_id)
            key (exchange, external_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
                exchange, accountRef, base, quote, type,
                nz(quantity), price, fee, feeAsset,
                Timestamp.from(ts), externalId
        );
    }

    private static BigDecimal nz(BigDecimal x) { return x == null ? BigDecimal.ZERO : x; }
}
