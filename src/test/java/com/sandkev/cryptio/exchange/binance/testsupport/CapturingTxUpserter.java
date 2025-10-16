package com.sandkev.cryptio.binance.testsupport;

import com.sandkev.cryptio.domain.Tx;
import com.sandkev.cryptio.tx.TxUpserter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class CapturingTxUpserter implements TxUpserter {
    public static record Tx(
            String exchange, String accountRef, String base, String quote, String type,
            BigDecimal quantity, BigDecimal price, BigDecimal fee, String feeAsset,
            Instant ts, String externalId
    ){}
    private final List<Tx> calls = new ArrayList<>();

    @Override public void upsert(String exchange, String accountRef, String base, String quote, String type,
                                 BigDecimal quantity, BigDecimal price, BigDecimal fee, String feeAsset,
                                 Instant ts, String externalId) {
        calls.add(new Tx(exchange, accountRef, base, quote, type, quantity, price, fee, feeAsset, ts, externalId));
    }
    public List<Tx> calls() { return calls; }


    @Override
    public com.sandkev.cryptio.domain.Tx convertTx(String exchange, String accountRef, String asset, String dir, BigDecimal qty, Instant ts, String orderId) {
        return null;
    }
    @Override
    public void write(com.sandkev.cryptio.domain.Tx tx) {

    }

}
