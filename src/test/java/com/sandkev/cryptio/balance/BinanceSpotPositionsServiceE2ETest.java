package com.sandkev.cryptio.balance;

import com.sandkev.cryptio.config.BinanceSpotConfig;
import com.sandkev.cryptio.config.BinanceSpotProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live E2E test hitting Binance Spot /api/v3/account via BinanceSpotPositionsService.
 *
 * Requirements:
 *  - Set environment variables:
 *      BINANCE_API_KEY
 *      BINANCE_SECRET_KEY
 *  - Optional overrides:
 *      BINANCE_BASE_URL (default https://api.binance.com)
 *      BINANCE_RECV_WINDOW (default 5000)
 *      BINANCE_TIMEOUT_MS (default 5000)
 *
 * The test:
 *  - Skips if keys are missing.
 *  - Calls the real endpoint and asserts the response shape.
 *  - If you truly have zero balances, the map can be empty; the call must still succeed.
 */
@Tag("live")
@ActiveProfiles("test")
@SpringBootTest(
        classes = {
                BinanceSpotConfig.class,
                BinanceSpotPositionsService.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnableConfigurationProperties(BinanceSpotProperties.class)
@TestPropertySource(properties = {
        "binance.spot.base-url=${BINANCE_BASE_URL:https://api.binance.com}",
        "binance.spot.api-key=${BINANCE_API_KEY:}",
        "binance.spot.secret-key=${BINANCE_SECRET_KEY:}",
        "binance.spot.recv-window=${BINANCE_RECV_WINDOW:5000}",
        "binance.spot.timeout-ms=${BINANCE_TIMEOUT_MS:5000}"
})
class BinanceSpotPositionsServiceE2ETest {

    @Autowired
    BinanceSpotPositionsService svc;

    @Autowired
    BinanceSpotProperties props;

    @BeforeEach
    void requireKeys() {
        Assumptions.assumeTrue(
                props.apiKey() != null && !props.apiKey().isBlank() &&
                        props.secretKey() != null && !props.secretKey().isBlank(),
                "BINANCE_API_KEY / BINANCE_SECRET_KEY not set — skipping live test."
        );
    }

    @Test
    void fetchSpotBalances_live_succeeds_and_returns_map() {
        try {
            Map<String, BigDecimal> balances = svc.fetchSpotBalances();

            for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
                System.out.println(entry);
            }


            // Basic assertions: call worked, map not null
            assertThat(balances).isNotNull();

            // If you expect a specific asset, uncomment to strengthen:
            // assertThat(balances).containsKey("USDT");

            // If non-empty, ensure all totals are >= 0
            balances.values().forEach(v -> {
                assertThat(v).isNotNull();
                assertThat(v.signum()).isGreaterThanOrEqualTo(0);
            });
        } catch (WebClientResponseException e) {
            // Helpful guidance for common live issues
            String msg = e.getRawStatusCode() + " " + e.getResponseBodyAsString();
            if (e.getRawStatusCode() == 401 || e.getRawStatusCode() == 403) {
                Assertions.fail("Binance auth failed (401/403). Check API key permissions and IP restrictions. Response: " + msg);
            } else if (e.getRawStatusCode() == 400 && e.getResponseBodyAsString().contains("Timestamp")) {
                Assertions.fail("Timestamp/recvWindow error. Ensure system clock is in sync (NTP) and recvWindow is sufficient. Response: " + msg);
            } else if (e.getRawStatusCode() == 429) {
                Assertions.fail("Rate-limited by Binance (429). Try again or reduce frequency. Response: " + msg);
            } else {
                Assertions.fail("Live call to Binance failed: " + msg, e);
            }
        }
    }
}
