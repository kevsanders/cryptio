package com.sandkev.cryptio.domain;

import org.springframework.data.jpa.domain.Specification;
import java.time.Instant;

public class KrakenTxSpecs {

    public static Specification<KrakenTxEntity> byPair(String pair) {
        return (r, q, cb) -> (pair == null || pair.isBlank())
                ? null
                : cb.equal(cb.upper(r.get("pair")), pair.toUpperCase());
    }

    public static Specification<KrakenTxEntity> byType(KrakenTxEntity.Type type) {
        return (r, q, cb) -> type == null ? null : cb.equal(r.get("type"), type);
    }

    public static Specification<KrakenTxEntity> byStatus(KrakenTxEntity.Status status) {
        return (r, q, cb) -> status == null ? null : cb.equal(r.get("status"), status);
    }

    public static Specification<KrakenTxEntity> from(Instant from) {
        return (r, q, cb) -> from == null ? null : cb.greaterThanOrEqualTo(r.get("ts"), from);
    }

    public static Specification<KrakenTxEntity> to(Instant to) {
        return (r, q, cb) -> to == null ? null : cb.lessThan(r.get("ts"), to);
    }

    public static Specification<KrakenTxEntity> text(String qStr) {
        return (r, q, cb) -> {
            if (qStr == null || qStr.isBlank()) return null;
            String like = "%" + qStr.toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(r.get("txid")), like),
                    cb.like(cb.lower(r.get("notes")), like),
                    cb.like(cb.lower(r.get("pair")), like),
                    cb.like(cb.lower(r.get("base")), like),
                    cb.like(cb.lower(r.get("quote")), like)
            );
        };
    }

    /** Combine all filters without using deprecated where(). */
    public static Specification<KrakenTxEntity> all(
            String pair,
            KrakenTxEntity.Type type,
            KrakenTxEntity.Status status,
            Instant from,
            Instant to,
            String q
    ) {
        // allOf(...) safely ignores null specs
        return Specification.allOf(
                byPair(pair),
                byType(type),
                byStatus(status),
                from(from),
                to(to),
                text(q)
        );
    }
}