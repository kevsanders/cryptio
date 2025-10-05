package com.sandkev.cryptio.ingest;

// src/main/java/com/sandkev/cryptio/ingest/IngestCheckpointDao.java
//package com.sandkev.cryptio.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class IngestCheckpointDao {
    private final JdbcTemplate jdbc;
    public IngestCheckpointDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Instant> get(String exchange, String account, String kind) {
        var list = jdbc.query(
                "select cursor_ts from ingest_checkpoint where exchange=? and account_ref=? and kind=?",
                (rs,i) -> rs.getTimestamp(1).toInstant(), exchange, account, kind);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    public void put(String exchange, String account, String kind, Instant ts, String cursorStr) {
        jdbc.update("""
            merge into ingest_checkpoint (exchange, account_ref, kind, cursor_str, cursor_ts, updated_at)
            key (exchange, account_ref, kind)
            values (?, ?, ?, ?, ?, current_timestamp)
        """, exchange, account, kind, cursorStr, Timestamp.from(ts));
    }
}
