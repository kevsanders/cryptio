// src/main/java/com/sandkev/cryptio/kraken/KrakenAssetUniverseDao.java
package com.sandkev.cryptio.exchange.kraken;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
public class KrakenAssetUniverseDao {
    private final JdbcTemplate jdbc;
    public KrakenAssetUniverseDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Distinct assets for Kraken account from balances + tx history. */
    public Set<String> assetsForAccount(String accountRef) {
        var out = new LinkedHashSet<String>();

        // From current snapshot (view you already use elsewhere)
        List<String> snap = jdbc.query("""
            select distinct asset
            from v_latest_balance
            where lower(exchange)='kraken' and account=?
        """, (rs,i) -> rs.getString(1), accountRef);

        // From tx base asset (anything we’ve ever seen)
        List<String> hist = jdbc.query("""
            select distinct base
            from tx
            where lower(exchange)='kraken' and account_ref=?
        """, (rs,i) -> rs.getString(1), accountRef);

        snap.stream().filter(s -> s!=null && !s.isBlank()).forEach(out::add);
        hist.stream().filter(s -> s!=null && !s.isBlank()).forEach(out::add);
        return out;
    }
}
