// src/main/java/com/sandkev/cryptio/tx/TxWriter.java
package com.sandkev.cryptio.tx;

import com.sandkev.cryptio.domain.Tx;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Repository
public class TxWriterImpl implements TxWriter, TxUpserter {

    private final JdbcTemplate jdbc;

    public TxWriterImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Idempotent upsert into tx using unique (exchange, external_id).
     * Keep 'id' auto-generated; never assign it.
     */
    @Override
    public void upsert(
            String exchange,
            String accountRef,
            String base,
            String quote,
            String type,                   // BUY, SELL, DEPOSIT, WITHDRAW, CONVERT_IN, CONVERT_OUT, REWARD
            BigDecimal quantity,           // positive magnitude
            BigDecimal price,              // nullable
            BigDecimal fee,
            String feeAsset,               // nullable
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

    @Override
    public void write(Tx tx) {
        // --- required fields ---
        if (tx.getExchange() == null) throw new IllegalArgumentException("Tx.exchange is required");
        if (tx.getAsset() == null)     throw new IllegalArgumentException("Tx.base is required");
        if (tx.getQuote() == null)    throw new IllegalArgumentException("Tx.quote is required");
        if (tx.getType() == null)     throw new IllegalArgumentException("Tx.type is required");
        if (tx.getTs() == null)       throw new IllegalArgumentException("Tx.ts is required");

        // external id: use provided or synthesize deterministically for idempotency
        String externalId = tx.getExternalId();
        if (externalId == null || externalId.isBlank()) {
            externalId = synthesizeExternalId(tx);
        }

        // fee asset defaults to quote if absent
        String feeAsset = (tx.getFeeAsset() == null || tx.getFeeAsset().isBlank())
                ? tx.getQuote()
                : tx.getFeeAsset();

        upsert(
                tx.getExchange(),
                tx.getAccountRef(),               // nullable
                tx.getAsset(),
                tx.getQuote(),
                tx.getType().toUpperCase(Locale.ROOT),
                tx.getQty(),
                tx.getPrice(),
                tx.getFee(),
                feeAsset,
                tx.getTs(),
                externalId
        );
    }

    /** Stable surrogate ID from key economic fields + timestamp (UUIDv3-style). */
    private static String synthesizeExternalId(Tx tx) {
        String canonical = String.join("|",
                n(tx.getExchange()),
                n(tx.getAccountRef()),
                n(tx.getAsset()),
                n(tx.getQuote()),
                n(tx.getType()),
                nBD(tx.getQty()),
                nBD(tx.getPrice()),
                nBD(tx.getFee()),
                n(tx.getFeeAsset()),
                String.valueOf(tx.getTs().toEpochMilli())
        );
        return UUID.nameUUIDFromBytes(sha256(canonical)).toString();
    }


    @Override
    public  Tx convertTx(String exchange, String accountRef, String asset, String dir, BigDecimal qty, Instant ts, String externalId) {
        Tx t = new Tx();
        t.setExchange(exchange);
        t.setAccountRef(accountRef);
        t.setAsset(asset);
        t.setQuote("N/A");
        t.setType(dir);                 // "CONVERT_IN" or "CONVERT_OUT"
        t.setQty(qty);
        t.setTs(ts);
        t.setExternalId(externalId);
        return t;
    }

    private static byte[] sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // extremely unlikely; fall back to raw bytes
            return s.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String n(String s) { return s == null ? "" : s; }
    private static String nBD(BigDecimal d) { return d == null ? "" : d.stripTrailingZeros().toPlainString(); }
}
