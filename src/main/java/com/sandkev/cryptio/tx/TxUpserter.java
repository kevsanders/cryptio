// src/main/java/com/sandkev/cryptio/tx/TxWriter.java
package com.sandkev.cryptio.tx;

import com.sandkev.cryptio.domain.Tx;

import java.math.BigDecimal;
import java.time.Instant;

public interface TxUpserter extends TxWriter {
    Tx convertTx(String exchange, String accountRef, String asset, String dir, BigDecimal qty, Instant ts, String orderId);

    void upsert(String exchange,
                String accountRef,
                String base,
                String quote,
                String type,
                BigDecimal quantity,
                BigDecimal price,
                BigDecimal fee,
                String feeAsset,
                Instant ts,
                String externalId);


}