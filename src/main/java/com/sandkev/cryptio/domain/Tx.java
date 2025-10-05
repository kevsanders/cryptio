// src/main/java/com/sandkev/cryptio/domain/Tx.java
package com.sandkev.cryptio.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "tx")
@Data
public class Tx {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String exchange;

    @Column(name = "account_ref")
    private String accountRef;

    // On-chain asset affected by this row (we used "base" in the schema)
    @Column(name = "base")
    private String asset;

    private String quote;

    // BUY/SELL/DEPOSIT/WITHDRAW/CONVERT_IN/CONVERT_OUT/REWARD
    private String type;

    @Column(name = "quantity")
    private BigDecimal qty;

    private BigDecimal price;

    private BigDecimal fee;

    @Column(name = "fee_asset")
    private String feeAsset;

    @Column(name = "ts")
    private Instant ts;

    @Column(name = "external_id", unique = true)
    private String externalId;

    // getters/setters omitted for brevity
    // (generate them in your IDE)
}
