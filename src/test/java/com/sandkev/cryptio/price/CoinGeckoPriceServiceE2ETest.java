package com.sandkev.cryptio.price;

import com.sandkev.cryptio.config.CoinGeckoConfig;
import com.sandkev.cryptio.config.CoinGeckoProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test that hits the real CoinGecko API.
 *
 * By default this uses the public endpoints (no API key required), but if you have a key you can pass it via:
 *   COINGECKO_API_KEY         -> value of the key
 *   COINGECKO_API_KEY_HEADER  -> x-cg-pro-api-key (Pro) or x-cg-demo-api-key (Demo)
 *
 * Marked @Tag("live") so you can include/exclude it in CI:
 *   ./gradlew test -Dgroups=live   (if you wire that), or just run normally from IDE.
 */
@Tag("live")
@SpringBootTest(
        classes = {
                CoinGeckoConfig.class,
                CoinGeckoPriceService.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnableConfigurationProperties(CoinGeckoProperties.class)
@TestPropertySource(properties = {
        "coingecko.base-url=https://api.coingecko.com/api/v3",
        "coingecko.user-agent=crypto-portfolio-e2e-test/0.1 (contact: test@example.com)",
        "coingecko.timeout-ms=4000",
        "coingecko.cache-ttl=PT5S",
        "coingecko.max-retries=2",
        "coingecko.backoff-ms=250",
        // Pick up API key from env if present; otherwise empty (public endpoints still work, subject to rate limits)
        "coingecko.api-key=${COINGECKO_API_KEY:}",
        "coingecko.api-key-header=${COINGECKO_API_KEY_HEADER:x-cg-demo-api-key}"
})
class CoinGeckoPriceServiceE2ETest {

    @Autowired
    private PriceService prices;

    @Test
    void simplePrice_bitcoin_and_ethereum_in_usd_should_be_positive() {
        try {
            Map<String, BigDecimal> res =
                    prices.getSimplePrice(Set.of("bitcoin", "ethereum"), "usd");

            assertThat(res)
                    .as("Should return prices for requested IDs")
                    .containsKeys("bitcoin", "ethereum");

            // Basic sanity bounds (positive and not astronomical)
            assertPositiveAndReasonable(res.get("bitcoin"));
            assertPositiveAndReasonable(res.get("ethereum"));
        } catch (WebClientRequestException ex) {
            // Gracefully skip if no internet/DNS/etc.
            Assumptions.assumeTrue(false, "Network unavailable or endpoint not reachable: " + ex.getMessage());
        }
    }

    @Test
    void tokenPrice_usdc_on_ethereum_should_be_near_one_usd() {
        try {
            // USDC on Ethereum mainnet
            String chain = "ethereum";
            String usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";

            Map<String, Map<String, BigDecimal>> res =
                    prices.getTokenPrice(chain, Set.of(usdc), Set.of("usd"));

            assertThat(res).containsKey(usdc.toLowerCase());

            BigDecimal price = res.get(usdc.toLowerCase()).get("usd");
            assertThat(price).isNotNull();

            // USDC should be roughly around 1 USD (allow roomy tolerance for market moves / decimals)
            assertThat(price.doubleValue()).isBetween(0.90, 1.10);
        } catch (WebClientRequestException ex) {
            Assumptions.assumeTrue(false, "Network unavailable or endpoint not reachable: " + ex.getMessage());
        }
    }

    @Test
    void supportedVs_includes_usd_and_gbp() {
        try {
            List<String> vs = prices.getSupportedVsCurrencies();
            assertThat(vs).contains("usd", "gbp");
        } catch (WebClientRequestException ex) {
            Assumptions.assumeTrue(false, "Network unavailable or endpoint not reachable: " + ex.getMessage());
        }
    }

    private static void assertPositiveAndReasonable(BigDecimal v) {
        assertThat(v).isNotNull();
        // > 0 and < 10 million USD as a sanity upper bound
        assertThat(v.signum()).isPositive();
        assertThat(v).isLessThan(new BigDecimal("10000000"));
    }
}
