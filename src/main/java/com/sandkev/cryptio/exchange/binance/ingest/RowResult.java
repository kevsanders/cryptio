package com.sandkev.cryptio.exchange.binance.ingest;

/** Outcome of handling a single upstream row. */
public record RowResult(int inserted, long eventTsMillis) {

    public static RowResult skip(long tsMillis) {           // no insert (dup, etc.)
        return new RowResult(0, tsMillis);
    }
    public static RowResult one(long tsMillis) {            // inserted 1 row
        return new RowResult(1, tsMillis);
    }
    public static RowResult many(int n, long tsMillis) {    // inserted n rows
        return new RowResult(n, tsMillis);
    }
}
