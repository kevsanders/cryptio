package com.sandkev.cryptio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "holding")
public class Holding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false, length=64)
    private String asset;

    @Column(nullable=false, precision=38, scale=18)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(nullable=false, precision=38, scale=18)
    private BigDecimal avgCost = BigDecimal.ZERO; // portfolio CCY

    @Version
    private long version;

    protected Holding() {} // for JPA

    public Holding(String asset, BigDecimal quantity, BigDecimal avgCost) {
        this.asset = asset;
        this.quantity = quantity;
        this.avgCost = avgCost;
    }

    public Long getId() { return id; }
    public String getAsset() { return asset; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getAvgCost() { return avgCost; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public void setAvgCost(BigDecimal avgCost) { this.avgCost = avgCost; }
}
