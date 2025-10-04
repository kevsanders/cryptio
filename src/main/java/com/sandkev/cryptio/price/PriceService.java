package com.sandkev.cryptio.price;

import java.math.BigDecimal;
import java.util.*;

public interface PriceService {
    /**
     * /simple/price: prices by CoinGecko coin IDs (e.g., "bitcoin,ethereum") in vs currencies (e.g., "usd,eur").
     * Returns map: { coinId -> priceInFirstVs } if single vs currency, else nested map.
     */
    Map<String, BigDecimal> getSimplePrice(Set<String> coinIds, String vsCurrency);

    Map<String, Map<String, BigDecimal>> getSimplePrice(Set<String> coinIds, Set<String> vsCurrencies);

    /**
     * /simple/token_price/{id}: prices by chain id (e.g., "ethereum", "base", "polygon-pos") and contract addresses.
     */
    Map<String, Map<String, BigDecimal>> getTokenPrice(String chainId, Set<String> contractAddresses, Set<String> vsCurrencies);

    /**
     * List of supported vs currencies (e.g., "usd","gbp","eur").
     */
    List<String> getSupportedVsCurrencies();

    /**
     * Lightweight coin list (id-symbol mapping) for resolving IDs.
     */
    List<CoinInfo> getCoinsList();

    record CoinInfo(String id, String symbol, String name) {}
}
