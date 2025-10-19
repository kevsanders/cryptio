package com.sandkev.cryptio.exchange.kraken;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.core.ParameterizedTypeReference;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KrakenSpotPositionsServiceTest {

    @Test
    void fetchSpotBalances_normalisesAndFilters() {
        KrakenSignedClient client = mock(KrakenSignedClient.class);

        Map<String, Object> krakenResponse = Map.of(
                "error", List.of(),
                "result", Map.of(
                        "XXBT", "0.123",
                        "ZUSD", "0",
                        "XETH", "2.5",
                        "ADA", "0.00000000"
                )
        );

        when(client.post(
                eq("/0/private/Balance"),
                eq(Map.of()),
                ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any()
        )).thenReturn(krakenResponse);

        var svc = new KrakenSpotPositionsService(client);
        var balances = svc.fetchSpotBalances();

        assertThat(balances)
                .containsEntry("BTC", new BigDecimal("0.123"))
                .containsEntry("ETH", new BigDecimal("2.5"))
                .doesNotContainKeys("USD", "ADA"); // zero balances filtered
    }
}
