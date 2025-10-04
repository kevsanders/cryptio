package com.sandkev.cryptio.ui;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import com.sandkev.cryptio.spot.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;

@Controller
public class PortfolioWebController {
    private final JdbcTemplate jdbc;
    private final BalanceIngestService ingest;

    public PortfolioWebController(JdbcTemplate jdbc, BalanceIngestService ingest) {
        this.jdbc = jdbc;
        this.ingest = ingest;
    }

    @GetMapping("/")
    public String home(Model model,
                       @RequestParam(name = "account", required = false, defaultValue = "primary") String account) {
        var rows = jdbc.query("""
                select exchange, account, asset, total_amt, as_of
                  from v_latest_balance
                 where account = ?
                 order by exchange, asset
                """,
                (ResultSet rs, int i) -> new Row(
                        rs.getString("exchange"),
                        rs.getString("account"),
                        rs.getString("asset"),
                        rs.getBigDecimal("total_amt"),
                        rs.getTimestamp("as_of").toInstant()
                ),
                account
        );

        // small totals by exchange
        var total = rows.stream().map(r -> r.total).reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("account", account);
        model.addAttribute("rows", rows);
        model.addAttribute("grandTotalQty", total); // plain quantities (not valued)
        return "index";
    }

    @PostMapping("/ingest/binance")
    public String ingestBinance(@RequestParam(defaultValue = "primary") String account) {
        ingest.ingestBinance(account);
        return "redirect:/?account=" + account;
    }

    @PostMapping("/ingest/kraken")
    public String ingestKraken(@RequestParam(defaultValue = "primary") String account) {
        ingest.ingestKraken(account);
        return "redirect:/?account=" + account;
    }

    record Row(String exchange, String account, String asset, BigDecimal total, java.time.Instant asOf) {}
}
