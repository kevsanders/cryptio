package com.sandkev.cryptio.domain;

import com.sandkev.cryptio.TxRepo;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PortfolioController {
    private final PnlService pnl;
    private final HoldingRepo holdings;
    private final TxRepo tx;

    public PortfolioController(PnlService pnl, HoldingRepo holdings, TxRepo tx) {
        this.pnl = pnl;
        this.holdings = holdings;
        this.tx = tx;
    }

    @GetMapping("/holdings")
    List<Holding> holdings(){ return holdings.findAll(); }
    @GetMapping("/pnl") List<PnlService.PnlLine> pnl(@RequestParam Map<String, BigDecimal> price){
        return pnl.compute(price); // pass prices from your price service in prod
    }
    @PostMapping("/tx") Transaction add(@RequestBody Transaction t){ return tx.save(t); }
}

