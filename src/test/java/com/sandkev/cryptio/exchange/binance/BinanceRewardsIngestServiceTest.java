package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.exchange.binance.testsupport.CapturingTxUpserter;
import com.sandkev.cryptio.exchange.binance.testsupport.FakeBinanceSignedClientFromClasspath;
import com.sandkev.cryptio.exchange.binance.testsupport.InMemoryCheckpointDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceRewardsIngestServiceTest {

    @Test
    void ingestsRewards() {
        var client = new FakeBinanceSignedClientFromClasspath(Map.of(
                "/sapi/v1/asset/assetDividend", "/binance/asset_dividend.json"
        ));
        var ckpt = new InMemoryCheckpointDao();
        var tx = new CapturingTxUpserter();
        var svc = new BinanceRewardsIngestService(client, ckpt, tx);

        int n = svc.ingest("acct", Instant.ofEpochMilli(1699999000000L));
        assertThat(n).isEqualTo(2);
        assertThat(tx.calls()).extracting(CapturingTxUpserter.Tx::type)
                .containsOnly("REWARD");
    }
}
