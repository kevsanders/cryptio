package com.sandkev.cryptio.tx;

// src/main/java/com/sandkev/cryptio/tx/TxController.java
//package com.sandkev.cryptio.tx;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class TxController {

    private final TxService svc;
    public TxController(TxService svc){ this.svc = svc; }

    @GetMapping("/tx")
    public String show(@RequestParam(required = false) String asset,
                       @RequestParam(required = false) String exchange,
                       @RequestParam(defaultValue = "primary") String account,
                       Model model) {
        model.addAttribute("asset", asset);
        model.addAttribute("exchange", exchange);
        model.addAttribute("account", account);
        model.addAttribute("rows", svc.list(asset, exchange, account));
        return "tx";
    }
}
