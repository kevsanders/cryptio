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

    private static final ParameterizedTypeReference<List<UserAssetDto>> USER_ASSET_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<BinanceAccountDto> ACCOUNT_TYPE =
            new ParameterizedTypeReference<>() {};

    private final BinanceSignedClient client;

    /**
     * Recommended: use /sapi/v3/asset/getUserAsset to include Funding/Earn etc.
     * If you truly want pure Spot only, keep fetchSpotBalancesFromAccount() and call that instead.
     */
    public Map<String, BigDecimal> fetchSpotBalances() {
        var params = new LinkedHashMap<String, Object>();
        // omitZeroBalances=true keeps payload small; needBtcValuation not required here
        params.put("needBtcValuation", false);
        params.put("omitZeroBalances", true);

        List<UserAssetDto> assets = client.post("/sapi/v3/asset/getUserAsset", params, USER_ASSET_LIST);
        if (assets == null || assets.isEmpty()) return Map.of();

        var out = new LinkedHashMap<String, BigDecimal>(assets.size());
        for (var a : assets) {
            // Sum the relevant fields you consider “owned”. Usually free + locked + freeze + withdrawing.
            BigDecimal total = bd(a.free)
                    .add(bd(a.locked))
                    .add(bd(a.freeze))
                    .add(bd(a.withdrawing))
                    .add(bd(a.ipoable))        // optional
                    .add(bd(a.btcValuation));  // NOT a quantity, ignore unless you want valuation
            // Better: only sum quantity-like fields
            total = bd(a.free).add(bd(a.locked)).add(bd(a.freeze)).add(bd(a.withdrawing));

            if (total.signum() != 0) {
                out.put(a.asset, total);
            }
        }
        return out;
    }

    /** If you need strictly SPOT-only, you can keep this and use /api/v3/account. */
    public Map<String, BigDecimal> fetchSpotBalancesFromAccount() {
        var params = new LinkedHashMap<String, Object>();
        params.put("omitZeroBalances", true); // supported on /api/v3/account

        var account = client.get("/api/v3/account", params, ACCOUNT_TYPE);
        if (account == null || account.balances() == null) return Map.of();

        var out = new LinkedHashMap<String, BigDecimal>(account.balances().size());
        for (var b : account.balances()) {
            BigDecimal total = bd(b.free()).add(bd(b.locked()));
            if (total.signum() != 0) out.put(b.asset(), total);
        }
        return out;
    }

    private static BigDecimal bd(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    // DTOs

    /** /sapi/v3/asset/getUserAsset */
    public static final class UserAssetDto {
        public String asset;
        public String free;
        public String locked;
        public String freeze;
        public String withdrawing;
        public String ipoable;        // sometimes present
        public String btcValuation;   // valuation amount (not a quantity)
        // there may be more fields; we only map what we use
    }

    /** /api/v3/account */
    public record BinanceAccountDto(List<BinanceBalanceDto> balances) {}
    public record BinanceBalanceDto(String asset, String free, String locked) {}
}
