package com.sandkev.cryptio.tx;

import com.sandkev.cryptio.domain.Tx;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TxService {
    private final TxRepository repo;

    public TxService(TxRepository repo) { this.repo = repo; }

    // Legacy 3-arg version
    public List<Tx> list(String asset, String exchange, String account) {
        return repo.findFiltered(n(asset), n(exchange), n(account));
    }

    // New 6-arg version for dashboard/transaction page
    public List<Tx> listFiltered(String exchange,
                                 String account,
                                 String asset,
                                 String type,
                                 Instant from,
                                 Instant to) {
        return repo.findFiltered(
                n(asset),
                n(exchange),
                n(account),
                n(type),
                from,
                to
        );
    }

    private static String n(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
