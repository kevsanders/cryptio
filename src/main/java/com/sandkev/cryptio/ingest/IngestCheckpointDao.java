// src/main/java/com/sandkev/cryptio/ingest/IngestCheckpointDao.java
package com.sandkev.cryptio.ingest;

import java.time.Instant;
import java.util.Optional;

public interface IngestCheckpointDao {
    java.util.OptionalLong getSince(String key);
    void saveSince(String key, long since);

    //tmp
    Optional<Instant> get(String exchange, String account, String kind);
    void put(String exchange, String account, String kind, Instant ts, String cursorStr);
}