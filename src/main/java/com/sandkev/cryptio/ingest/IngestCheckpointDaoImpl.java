// src/main/java/com/sandkev/cryptio/ingest/IngestCheckpointDao.java
package com.sandkev.cryptio.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

@Repository
public class IngestCheckpointDaoImpl implements IngestCheckpointDao {

    private final JdbcTemplate jdbc;

    public IngestCheckpointDaoImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ---- Legacy API (kept for compatibility, used elsewhere in code) ----
    public Optional<Instant> get(String exchange, String account, String kind) {
        var list = jdbc.query(
                "select cursor_ts from ingest_checkpoint where exchange=? and account_ref=? and kind=?",
                (rs,i) -> rs.getTimestamp(1).toInstant(), exchange, account, kind
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    public void put(String exchange, String account, String kind, Instant ts, String cursorStr) {
        jdbc.update("""
            merge into ingest_checkpoint (exchange, account_ref, kind, cursor_str, cursor_ts, updated_at)
            key (exchange, account_ref, kind)
            values (?, ?, ?, ?, ?, current_timestamp)
        """, exchange, account, kind, cursorStr, Timestamp.from(ts));
    }

    // ---- New interface implementation ----

    @Override
    public OptionalLong getSince(String key) {
        KeyParts k = parse(key);
        var list = jdbc.query(
                "select cursor_ts from ingest_checkpoint where exchange=? and account_ref=? and kind=?",
                (rs,i) -> rs.getTimestamp(1).toInstant().getEpochSecond(),
                k.exchange, k.account, k.kind
        );
        if (list.isEmpty()) return OptionalLong.empty();
        Long sec = list.getFirst();
        return (sec == null) ? OptionalLong.empty() : OptionalLong.of(sec);
    }

    @Override
    public void saveSince(String key, long since) {
        KeyParts k = parse(key);
        Instant ts = Instant.ofEpochSecond(since);
        jdbc.update("""
            merge into ingest_checkpoint (exchange, account_ref, kind, cursor_str, cursor_ts, updated_at)
            key (exchange, account_ref, kind)
            values (?, ?, ?, ?, ?, current_timestamp)
        """, k.exchange, k.account, k.kind, String.valueOf(since), Timestamp.from(ts));
    }

    // ---- helpers ----

    private static final String DEFAULT_EXCHANGE = "*";
    private static final String DEFAULT_ACCOUNT  = "*";
    private static final String DEFAULT_ACCOUNT_FOR_2PART = "spot";

    private static KeyParts parse(String key) {
        if (key == null || key.isBlank()) {
            return new KeyParts(DEFAULT_EXCHANGE, DEFAULT_ACCOUNT, "default");
        }
        String[] parts = key.trim().toLowerCase(Locale.ROOT).split("\\.");
        return switch (parts.length) {
            case 3 -> new KeyParts(parts[0], parts[1], parts[2]);
            case 2 -> new KeyParts(parts[0], DEFAULT_ACCOUNT_FOR_2PART, parts[1]);
            default -> new KeyParts(DEFAULT_EXCHANGE, DEFAULT_ACCOUNT, parts[0]);
        };
    }

    private record KeyParts(String exchange, String account, String kind) {}
}
