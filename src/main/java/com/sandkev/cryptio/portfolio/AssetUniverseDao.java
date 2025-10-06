// src/main/java/com/sandkev/cryptio/portfolio/AssetUniverseDao.java
package com.sandkev.cryptio.portfolio;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
public class AssetUniverseDao {
    private final JdbcTemplate jdbc;
    public AssetUniverseDao(JdbcTemplate jdbc){ this.jdbc = jdbc; }

    /** Distinct assets for an exchange/account from latest snapshots + tx history. */
    public Set<String> assetsForAccount(String exchange, String accountRef) {
        var out = new LinkedHashSet<String>();

        // from latest snapshot view
        List<String> snap = jdbc.query(
                """
                select distinct asset
                from v_latest_balance
                where lower(exchange)=lower(?) and account=?
                """, (rs,i) -> rs.getString(1), exchange, accountRef);

        // from tx rows (base column)
        List<String> hist = jdbc.query(
                """
                select distinct base
                from tx
                where lower(exchange)=lower(?) and account_ref=?
                """, (rs,i) -> rs.getString(1), exchange, accountRef);

        snap.stream().filter(s -> s!=null && !s.isBlank()).forEach(out::add);
        hist.stream().filter(s -> s!=null && !s.isBlank()).forEach(out::add);

        return out;
    }
}

