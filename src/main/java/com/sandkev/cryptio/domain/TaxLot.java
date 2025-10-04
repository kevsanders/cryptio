package com.sandkev.cryptio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "tax_lot")
public class TaxLot {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "asset", nullable=false, length=64)
  private String asset;   // <-- was Asset; now String

  @Column(name = "qty_open", precision=38, scale=18, nullable=false)
  private BigDecimal qtyOpen;

  @Column(name = "cost_basis", precision=38, scale=18, nullable=false)
  private BigDecimal costBasis;

  @Column(name = "opened_at", nullable=false)
  private Instant openedAt;

  protected TaxLot() {}
  public TaxLot(String asset, BigDecimal qtyOpen, BigDecimal costBasis, Instant openedAt) {
    this.asset = asset; this.qtyOpen = qtyOpen; this.costBasis = costBasis; this.openedAt = openedAt;
  }
  // getters/setters...
}