package com.sandkev.cryptio.exchange.kraken;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Map.entry;

@Slf4j
@Service
@RequiredArgsConstructor
public class KrakenSpotPositionsService {

    private static final String BALANCE_PATH = "/0/private/Balance";

    private final KrakenSignedClient kraken;

    /**
     * Calls POST /0/private/Balance and returns { asset -> balance } for nonzero balances.
     * Kraken symbols are normalised (e.g., XXBT -> BTC, XETH -> ETH).
     */
    public Map<String, BigDecimal> fetchSpotBalances() {
        // Response shape: { "error": [..], "result": { "XXBT": "0.1", "ZUSD": "15.0", ... } }
        Map<String, Object> response = kraken.post(
                BALANCE_PATH,
                Map.of(), // no params required
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        if (response == null) {
            log.warn("Kraken {} returned null body; treating as empty.", BALANCE_PATH);
            return Map.of();
        }

        @SuppressWarnings("unchecked")
        List<?> errors = (List<?>) response.getOrDefault("error", List.of());
        if (!errors.isEmpty()) {
            // Typical: ["EAPI:Invalid key","EGeneral:Rate limit exceeded", ...]
            throw new RuntimeException("Kraken /0/private/Balance error(s): " + errors);
        }

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) response.getOrDefault("result", Map.of());

        var out = new LinkedHashMap<String, BigDecimal>(Math.max(8, result.size()));
        for (var e : result.entrySet()) {
            String asset = normaliseAsset(e.getKey());
            BigDecimal balance = new BigDecimal(e.getValue());
            if (balance.signum() != 0) {
                out.merge(asset, balance, BigDecimal::add);
            }
        }
        return out;
    }

    // ---- Asset normalisation (Kraken's X*/Z* prefixes to common tickers) ----
    private static final Map<String, String> ASSET_MAP = Map.ofEntries(
            entry("XXBT", "BTC"),
            entry("XBT", "BTC"),
            entry("XETH", "ETH"),
            entry("ZUSD", "USD"),
            entry("ZEUR", "EUR"),
            entry("ZGBP", "GBP"),
            entry("ZUSDT", "USDT"),
            entry("ZUSDC", "USDC"),
            entry("BT.F", "BTC"),
            entry("XBT.F", "BTC"),
            entry("ADA.F", "ADA"),
            entry("ATOM.F", "ATOM")
    );
    private static final Pattern LEADING_XZ = Pattern.compile("^[XZ]");

//    static String normaliseAsset(String krakenAsset) {
//        if (krakenAsset == null) return null;
//        String mapped = ASSET_MAP.get(krakenAsset);
//        if (mapped != null) return mapped;
//        // Strip leading 'X'/'Z' if present, e.g. XREP -> REP
//        return LEADING_XZ.matcher(krakenAsset).replaceFirst("");
//    }
    static String normaliseAsset(String krakenAsset) {
        if (krakenAsset == null) return null;
        if (ASSET_MAP.containsKey(krakenAsset)) return ASSET_MAP.get(krakenAsset);
        // Strip leading 'X'/'Z' if present, e.g. XREP -> REP
        return LEADING_XZ.matcher(krakenAsset).replaceFirst("");
    }
}
