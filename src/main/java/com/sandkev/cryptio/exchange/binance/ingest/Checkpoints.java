// src/main/java/com/sandkev/cryptio/exchange/binance/ingest/Checkpoints.java
package com.sandkev.cryptio.exchange.binance.ingest;

import com.sandkev.cryptio.ingest.IngestCheckpointDao;

import java.time.Instant;
import java.util.Optional;

public final class Checkpoints {
    private Checkpoints() {}
    public static long startMs(IngestCheckpointDao ckpt, String exch, String acct, String kind, Instant since) {
        Optional<Instant> cp = ckpt.get(exch, acct, kind);
        long s = since != null ? since.toEpochMilli() : 0L;
        return Math.max(cp.map(Instant::toEpochMilli).orElse(0L), s);
    }
    public static void save(IngestCheckpointDao ckpt, String exch, String acct, String kind, long ms) {
        ckpt.put(exch, acct, kind, Instant.ofEpochMilli(ms), null);
    }
}
