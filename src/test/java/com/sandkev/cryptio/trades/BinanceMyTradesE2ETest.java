package com.sandkev.cryptio.trades;


import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*; // includes absent()
import static org.assertj.core.api.Assertions.assertThat;

//@ActiveProfiles("h2")
//@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@ActiveProfiles("h2")
@Disabled
class BinanceMyTradesE2ETest {

    static WireMockServer wm;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        wm = new WireMockServer(0);
        wm.start();
        r.add("binance.spot.base-url", () -> "http://localhost:" + wm.port()); // whatever your prop name is
        r.add("binance.api-key", () -> "test-key");
        r.add("binance.secret-key", () -> "test-secret");
    }

    @AfterAll
    static void stop() {
        if (wm != null) wm.stop();
    }

    @Autowired
    BinanceTradeIngestService svc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetDbAndWiremock() {
        jdbc.update("delete from tx where exchange='binance'");
        wm.resetAll();
    }

    @Test
    void myTrades_usesStartTime_thenFromId_only_and_upserts() {
        // FIRST page: requires startTime present, fromId absent
        wm.stubFor(get(urlPathEqualTo("/api/v3/myTrades"))
                .withQueryParam("symbol", equalTo("BTCUSDT"))
                .withQueryParam("startTime", matching("\\d+"))
                .withQueryParam("limit", equalTo("1000"))
                .withQueryParam("recvWindow", equalTo("60000"))
                .withQueryParam("timestamp", matching("\\d+"))
                .withQueryParam("signature", matching("[0-9a-f]{64}"))
                .withQueryParam("fromId", absent())     // <-- instead of withoutQueryParam
                .willReturn(okJson("""
                          [
                            {"id": 100, "orderId": 1, "price":"50000","qty":"0.001","commission":"0.00000001","commissionAsset":"BTC","time": 1700000000000, "isBuyer": true},
                            {"id": 101, "orderId": 2, "price":"51000","qty":"0.002","commission":"0.1","commissionAsset":"USDT","time": 1700000100000, "isBuyer": false}
                          ]
                        """)));

        // SECOND page: requires fromId present, startTime absent
        wm.stubFor(get(urlPathEqualTo("/api/v3/myTrades"))
                .withQueryParam("symbol", equalTo("BTCUSDT"))
                .withQueryParam("fromId", equalTo("102"))
                .withQueryParam("limit", equalTo("1000"))
                .withQueryParam("recvWindow", equalTo("60000"))
                .withQueryParam("timestamp", matching("\\d+"))
                .withQueryParam("signature", matching("[0-9a-f]{64}"))
                .withQueryParam("startTime", absent())  // <-- instead of withoutQueryParam
                .willReturn(okJson("[]")));

        // Run ingest for a single symbol path in your service (call wrapper that picks BTCUSDT)
        int inserted = svc.ingestMyTrades("primary", Instant.ofEpochMilli(1699999900000L)); // since slightly before first trade

        assertThat(inserted).isGreaterThanOrEqualTo(2);

        // Assert they were upserted (idempotent)
        Integer c = jdbc.queryForObject("select count(*) from tx where exchange='binance' and type in ('BUY','SELL')", Integer.class);
        assertThat(c).isGreaterThanOrEqualTo(2);

        // Optional: assert external_id patterns exist
        List<String> ext = jdbc.query("select external_id from tx where exchange='binance' order by ts",
                (rs, i) -> rs.getString(1));
        assertThat(ext).anyMatch(s -> s.equals("trade:BTCUSDT:100"));
        assertThat(ext).anyMatch(s -> s.equals("trade:BTCUSDT:101"));

        // Verify WireMock saw both shapes (startTime first, then fromId)
        wm.verify(getRequestedFor(urlPathEqualTo("/api/v3/myTrades"))
                .withQueryParam("startTime", matching("\\d+"))
                .withQueryParam("fromId", absent()));

        wm.verify(getRequestedFor(urlPathEqualTo("/api/v3/myTrades"))
                .withQueryParam("fromId", equalTo("102"))
                .withQueryParam("startTime", absent()));
    }
}
