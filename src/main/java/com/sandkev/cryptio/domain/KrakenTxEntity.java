package com.sandkev.cryptio.domain;

import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(
        name = "kraken_tx",
        indexes = {
                @Index(name = "idx_krakentx_ts", columnList = "ts"),
                @Index(name = "idx_krakentx_pair", columnList = "pair"),
                @Index(name = "idx_krakentx_type", columnList = "type"),
                @Index(name = "idx_krakentx_status", columnList = "status")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uc_krakentx_txid", columnNames = {"txid"})
        }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class KrakenTxEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(length = 36, updatable = false, nullable = false)
    private String id;

    /**
     * Trade/ledger timestamp (UTC).
     */
    @Column(nullable = false, updatable = false)
    private Instant ts;

    /**
     * Convenience denormalized symbol: e.g. "ETH/EUR".
     * Kept along with base/quote for easy filtering/reporting.
     */
    @Column(length = 24, nullable = false)
    private String pair;

    @Column(length = 16, nullable = false)
    private String base;

    @Column(length = 16, nullable = false)
    private String quote;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Status status;

    /**
     * Monetary/quantity fields use wide precision for crypto/fiat pairs.
     */
    @Column(precision = 38, scale = 18)
    private BigDecimal price;

    @Column(precision = 38, scale = 18)
    private BigDecimal amount;  // base amount

    @Column(precision = 38, scale = 18)
    private BigDecimal total;   // quote total

    @Column(precision = 38, scale = 18)
    private BigDecimal fee;     // quote fee

    /**
     * Kraken identifiers (not always present for deposits/withdrawals).
     */
    @Column(length = 64, unique = true)
    private String txid;     // exchange transaction id (unique when present)

    @Column(length = 64)
    private String orderId;

    @Column(length = 64)
    private String tradeId;

    /**
     * Optional external account/wallet logical owner (if multi-account).
     */
    @Column(length = 64)
    private String accountId;

    /**
     * User-managed tags (e.g., “airdrop”, “rebalance”, “fees”).
     */
    @ElementCollection
    @CollectionTable(name = "kraken_tx_tag", joinColumns = @JoinColumn(name = "tx_id"))
    @Column(name = "tag", length = 40, nullable = false)
    @Builder.Default
    private Set<String> tags = new LinkedHashSet<>();

    @Column(length = 2048)
    private String notes;

    @Version
    private long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    // ---- Enums ----
    public enum Type {
        buy, sell, deposit, withdrawal, staking, fee
    }

    public enum Status {
        NEW, PENDING, RECONCILED, ERROR;

        public static Status fromUi(String s) {
            if (s == null) return NEW;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "new" -> NEW;
                case "pending" -> PENDING;
                case "reconciled" -> RECONCILED;
                case "error" -> ERROR;
                default -> NEW;
            };
        }
    }

    // ---- Helpers ----
    public void setPairFromBaseQuote() {
        if (base != null && quote != null) this.pair = base + "/" + quote;
    }

    public void addTag(String tag) {
        if (tag != null && !tag.isBlank()) {
            if (tags == null) tags = new LinkedHashSet<>();
            tags.add(tag.trim());
        }
    }

    @PrePersist
    @PreUpdate
    private void prePersistUpdate() {
        if (pair == null && base != null && quote != null) {
            setPairFromBaseQuote();
        }
    }

    // equals/hashCode by id for entity identity
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KrakenTxEntity that)) return false;
        return id != null && id.equals(that.id);
    }
    @Override
    public int hashCode() { return Objects.hashCode(id); }
}

