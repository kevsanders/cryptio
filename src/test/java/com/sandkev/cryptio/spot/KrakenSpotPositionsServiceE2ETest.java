package com.sandkev.cryptio.spot;

import com.sandkev.cryptio.config.KrakenSpotConfig;
import com.sandkev.cryptio.config.KrakenSpotProperties;
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

@Tag("live")
@ActiveProfiles("test")
@SpringBootTest(
        classes = { KrakenSpotConfig.class, KrakenSpotPositionsService.class },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnableConfigurationProperties(KrakenSpotProperties.class)
@TestPropertySource(properties = {
        "kraken.spot.base-url=${KRAKEN_BASE_URL:https://api.kraken.com}",
        "kraken.spot.api-key=${KRAKEN_API_KEY:}",
        "kraken.spot.secret-key=${KRAKEN_SECRET_KEY:}",
        "kraken.spot.timeout-ms=${KRAKEN_TIMEOUT_MS:5000}"
})
class KrakenSpotPositionsServiceE2ETest {

    @Autowired KrakenSpotPositionsService svc;
    @Autowired KrakenSpotProperties props;

    @BeforeEach
    void requireKeys() {
        Assumptions.assumeTrue(
                props.apiKey() != null && !props.apiKey().isBlank()
                        && props.secretKey() != null && !props.secretKey().isBlank(),
                "KRAKEN_API_KEY / KRAKEN_SECRET_KEY not set — skipping live test."
        );
    }

    @Test
    void fetchSpotBalances_live_succeeds_and_returns_map() {
        try {
            Map<String, BigDecimal> balances = svc.fetchSpotBalances();

            assertThat(balances).isNotNull();
            // OK if empty; if non-empty ensure non-negative
            balances.values().forEach(v -> {
                assertThat(v).isNotNull();
                assertThat(v.signum()).isGreaterThanOrEqualTo(0);
            });

            // If you expect stable coins typically present, uncomment:
            // assertThat(balances.keySet()).anyMatch(a -> a.equals("USD") || a.equals("USDT") || a.equals("USDC"));

        } catch (WebClientResponseException e) {
            String msg = e.getRawStatusCode() + " " + e.getResponseBodyAsString();
            if (e.getRawStatusCode() == 401 || e.getRawStatusCode() == 403) {
                Assertions.fail("Kraken auth failed (401/403). Check API key permissions/IP/2FA. Response: " + msg);
            } else if (e.getRawStatusCode() == 429) {
                Assertions.fail("Rate limited by Kraken (429). Try again later. Response: " + msg);
            } else {
                Assertions.fail("Live call to Kraken failed: " + msg, e);
            }
        }
    }
}
