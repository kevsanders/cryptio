package com.sandkev.cryptio.balance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BinanceSpotPositionsService {

    private static final ParameterizedTypeReference<BinanceAccountDto> ACCOUNT_TYPE =
            new ParameterizedTypeReference<>() {};

    private final BinanceSignedClient binanceSignedClient;


    /**
     * Fetch spot balances from Binance /api/v3/account and return totals per asset (free + locked).
     * Zeros are omitted.
     */
    public Map<String, BigDecimal> fetchSpotBalances() {
        // You can request Binance to omit zeros (supported on /api/v3/account)
        var params = new LinkedHashMap<String, Object>();
        params.put("omitZeroBalances", true);

        var account = binanceSignedClient.get("/api/v3/account", params, ACCOUNT_TYPE);
        if (account == null || account.balances() == null) return Map.of();

        // balances is an array of { asset, free, locked }
        var out = new LinkedHashMap<String, BigDecimal>(account.balances().size());
        for (var b : account.balances()) {
            // Binance returns free/locked as strings (e.g., "0.00000000")
            BigDecimal free   = safeDecimal(b.free());
            BigDecimal locked = safeDecimal(b.locked());
            BigDecimal total  = free.add(locked);
            if (total.signum() != 0) {
                out.put(b.asset(), total);
            }
        }
        return out;
    }

    private static BigDecimal safeDecimal(String v) {
        if (v == null || v.isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(v);
    }

    // --- Minimal DTOs matching /api/v3/account payload ---
    public record BinanceAccountDto(List<BinanceBalanceDto> balances) {}
    public record BinanceBalanceDto(String asset, String free, String locked) {}
}
