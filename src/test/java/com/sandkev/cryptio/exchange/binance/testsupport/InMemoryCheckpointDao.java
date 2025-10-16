package com.sandkev.cryptio.exchange.binance.testsupport;

import com.sandkev.cryptio.ingest.IngestCheckpointDao;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCheckpointDao implements IngestCheckpointDao {
    private final Map<String, Instant> map = new ConcurrentHashMap<>();
    private String key(String ex, String acct, String kind) { return ex+"|"+acct+"|"+kind; }

    @Override public Optional<Instant> get(String ex, String acct, String kind) {
        return Optional.ofNullable(map.get(key(ex,acct,kind)));
    }
    @Override public void put(String ex, String acct, String kind, Instant since, String note) {
        map.put(key(ex,acct,kind), since);
    }

    // If you have other methods on your interface, add no-op or minimal impls here.
    @Override
    public OptionalLong getSince(String key) {
        return null;
    }

    @Override
    public void saveSince(String key, long since) {

    }

}
