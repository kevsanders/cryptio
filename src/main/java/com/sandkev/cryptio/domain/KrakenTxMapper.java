package com.sandkev.cryptio.domain;

import com.sandkev.cryptio.web.kraken.KrakenTransactionController;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KrakenTxMapper {

    public KrakenTransactionController.KrakenTxView toView(KrakenTxEntity e) {
        if (e == null) return null;
        return new KrakenTransactionController.KrakenTxView(
                e.getId(),
                e.getTs() != null ? ZonedDateTime.ofInstant(e.getTs(), ZoneOffset.UTC) : null,
                e.getPair(),
                e.getBase(),
                e.getQuote(),
                e.getType() != null ? e.getType().name().toLowerCase() : null,
                e.getStatus() != null ? e.getStatus().name().toLowerCase() : null,
                nz(e.getPrice()),
                nz(e.getAmount()),
                nz(e.getTotal()),
                nz(e.getFee()),
                e.getTags() != null ? List.copyOf(e.getTags()) : List.of(),
                e.getTxid(),
                e.getNotes()
        );
    }

    public void applyViewToEntity(KrakenTransactionController.KrakenTxView v, KrakenTxEntity e) {
        if (v == null || e == null) return;
        e.setBase(v.getBase());
        e.setQuote(v.getQuote());
        e.setPair(v.getPair() != null ? v.getPair() :
                (v.getBase() != null && v.getQuote() != null ? v.getBase() + "/" + v.getQuote() : null));
        e.setPrice(v.getPrice());
        e.setAmount(v.getAmount());
        e.setTotal(v.getTotal());
        e.setFee(v.getFee());
        e.setTxid(v.getTxid());
        e.setNotes(v.getNotes());
        if (v.getTs() != null) e.setTs(v.getTs().toInstant());
        if (v.getType() != null) {
            try { e.setType(KrakenTxEntity.Type.valueOf(v.getType().toLowerCase())); } catch (IllegalArgumentException ignored) {}
        }
        if (v.getStatus() != null) {
            switch (v.getStatus().toLowerCase()) {
                case "new" -> e.setStatus(KrakenTxEntity.Status.NEW);
                case "pending" -> e.setStatus(KrakenTxEntity.Status.PENDING);
                case "reconciled" -> e.setStatus(KrakenTxEntity.Status.RECONCILED);
                case "error" -> e.setStatus(KrakenTxEntity.Status.ERROR);
                default -> {}
            }
        }
        if (v.getBase() != null && v.getQuote() != null && e.getPair() == null) {
            e.setPairFromBaseQuote();
        }
    }

    private BigDecimal nz(BigDecimal b) { return b == null ? null : b; }
}
