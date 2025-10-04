package com.sandkev.cryptio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "tx")
public class Transaction {
    public enum TxType { DEPOSIT, WITHDRAWAL, BUY, SELL, FEE, TRANSFER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length=64) private String exchange;
    @Column(length=64) private String accountRef;
    @Column(length=32) private String base;
    @Column(length=32) private String quote;

    @Enumerated(EnumType.STRING)
    @Column(length=16, nullable=false)
    private TxType type;

    @Column(precision=38, scale=18, nullable=false) private BigDecimal quantity;
    @Column(precision=38, scale=18) private BigDecimal price;
    @Column(precision=38, scale=18) private BigDecimal fee;
    @Column(length=32) private String feeAsset;

    @Column(nullable=false) private Instant ts;
    @Column(length=96) private String externalId;

    protected Transaction() {}
    // getters/setters…
}
