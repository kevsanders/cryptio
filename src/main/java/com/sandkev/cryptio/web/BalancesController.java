// src/main/java/com/sandkev/cryptio/web/BalancesController.java
package com.sandkev.cryptio.web;

import com.sandkev.cryptio.portfolio.BalanceViewDao;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
//@RequestMapping("/v2")
public class BalancesController {

    private final BalanceViewDao balances;

    public BalancesController(BalanceViewDao balances) {
        this.balances = balances;
    }

    @GetMapping("/balances")
    public String balances(@RequestParam(required = false) String exchange,
                           @RequestParam(defaultValue = "primary") String account,
                           Model model) {

        var rows = balances.latest(exchange, account);
        model.addAttribute("exchange", exchange);
        model.addAttribute("account", account);
        model.addAttribute("rows", rows); // fields: exchange, account, asset, free, locked, total, asOf

        return "v2/balances";
    }
}
