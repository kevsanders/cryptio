package com.sandkev.cryptio.exchange.kraken;

import java.util.concurrent.atomic.AtomicLong;

final class KrakenNonce {
    private static final AtomicLong LAST = new AtomicLong(System.currentTimeMillis());

    static String next() {
        // Ensure strictly increasing even within the same millisecond
        while (true) {
            long now = System.currentTimeMillis();
            long prev = LAST.get();
            long next = Math.max(now, prev + 1);
            if (LAST.compareAndSet(prev, next)) {
                return String.valueOf(next);
            }
        }
    }

    private KrakenNonce() {}
}
