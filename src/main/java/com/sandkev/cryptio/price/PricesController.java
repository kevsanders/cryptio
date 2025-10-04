package com.sandkev.cryptio.price;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/prices")
class PricesController {

    private final PriceService prices;

    PricesController(PriceService prices) { this.prices = prices; }

    // GET /prices/simple?ids=bitcoin,ethereum&vs=usd
    @GetMapping("/simple")
    Map<String, BigDecimal> simple(
            @RequestParam Set<String> ids,
            @RequestParam(defaultValue = "usd") String vs) {
        return prices.getSimplePrice(ids, vs);
    }

    // GET /prices/token?chain=ethereum&contracts=0x...,...&vs=usd,gbp
    @GetMapping("/token")
    Map<String, Map<String, BigDecimal>> token(
            @RequestParam String chain,
            @RequestParam Set<String> contracts,
            @RequestParam Set<String> vs) {
        return prices.getTokenPrice(chain, contracts, vs);
    }

    @GetMapping("/supported-vs")
    List<String> supportedVs() { return prices.getSupportedVsCurrencies(); }
}
