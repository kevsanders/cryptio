// src/main/java/com/sandkev/cryptio/portfolio/BalanceViewDao.java
package com.sandkev.cryptio.portfolio;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public class BalanceViewDao {
    public record Row(String exchange, String account, String asset,
                      BigDecimal free, BigDecimal locked, BigDecimal total, Instant asOf) {}
    private final JdbcTemplate jdbc;
    public BalanceViewDao(JdbcTemplate jdbc){ this.jdbc = jdbc; }

    public List<Row> latest(String exchange, String account) {
        return jdbc.query("""
      select exchange, account, asset, free_amt, locked_amt, total_amt, as_of
      from v_latest_balance
      where (? is null or lower(exchange)=lower(?))
        and (? is null or account=?)
      order by exchange, asset
    """, (rs,i) -> new Row(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                rs.getTimestamp(7).toInstant()
        ), nz(exchange), nz(exchange), nz(account), nz(account));
    }
    private static String nz(String s){ return (s==null||s.isBlank())?null:s; }
}
