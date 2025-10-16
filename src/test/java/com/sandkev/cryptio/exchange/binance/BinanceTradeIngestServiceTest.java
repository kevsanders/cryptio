package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.exchange.binance.testsupport.CapturingTxUpserter;
import com.sandkev.cryptio.exchange.binance.testsupport.FakeBinanceSignedClientFromClasspath;
import com.sandkev.cryptio.exchange.binance.testsupport.InMemoryCheckpointDao;
import com.sandkev.cryptio.portfolio.AssetUniverseDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BinanceTradeIngestServiceTest {

    @Test
    void ingestsTradesForAllAssets() {
        var client = new FakeBinanceSignedClientFromClasspath(Map.of(
                "/api/v3/myTrades", "/binance/myTrades_BTCUSDT.json"
        ));
        var ckpt = new InMemoryCheckpointDao();
        var tx = new CapturingTxUpserter();

        AssetUniverseDao assets = mock(AssetUniverseDao.class);
        when(assets.assetsForAccount("binance", "acct")).thenReturn(Set.of("BTC"));
        BinanceSymbolMapper mapper = mock(BinanceSymbolMapper.class);
        when(mapper.toMarket("BTC")).thenReturn("BTCUSDT");

        var svc = new BinanceTradeIngestService(client, tx, ckpt, assets, mapper);

        int n = svc.ingestAllAssets("acct", Instant.ofEpochMilli(1699999000000L));
        assertThat(n).isEqualTo(2);
        assertThat(tx.calls()).extracting(CapturingTxUpserter.Tx::externalId)
                .containsExactlyInAnyOrder("trade:BTCUSDT:10","trade:BTCUSDT:11");
    }
}
