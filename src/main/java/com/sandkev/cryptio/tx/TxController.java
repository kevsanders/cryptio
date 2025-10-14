// src/main/java/com/sandkev/cryptio/web/TxController.java
package com.sandkev.cryptio.tx;

import com.sandkev.cryptio.tx.TxService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@Controller
public class TxController {

  private final TxService txService;

  public TxController(TxService txService) {
    this.txService = txService;
  }

  @GetMapping("/tx")
  public String list(@RequestParam(required = false) String exchange,
                     @RequestParam(defaultValue = "primary") String account,
                     @RequestParam(required = false) String asset,
                     @RequestParam(required = false) String type,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                     Model model) {

    var rows = txService.listFiltered(exchange, account, asset, type, from, to);
    model.addAttribute("exchange", exchange);
    model.addAttribute("account", account);
    model.addAttribute("asset", asset);
    model.addAttribute("type", type);
    model.addAttribute("from", from);
    model.addAttribute("to", to);
    model.addAttribute("rows", rows); // rows must expose fields used by template: ts, exchange, accountRef, asset, quote, type, qty, price, fee, feeAsset, externalId

    return "tx";
  }
}
