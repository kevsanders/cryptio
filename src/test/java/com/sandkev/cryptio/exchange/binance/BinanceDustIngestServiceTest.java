package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.exchange.binance.testsupport.CapturingTxUpserter;
import com.sandkev.cryptio.exchange.binance.testsupport.InMemoryCheckpointDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceDustIngestServiceTest {

    @Test
    void ingestsDustDetails() {
        var client = new CapturingTxUpserter.FakeBinanceSignedClientFromClasspath(Map.of(
                "/sapi/v1/asset/dribblet", "/binance/asset_dribblet.json"
        ));
        var ckpt = new InMemoryCheckpointDao();
        var tx = new CapturingTxUpserter();
        var svc = new BinanceDustIngestService(client, ckpt, tx);

        int n = svc.ingest("acct", Instant.ofEpochMilli(1699999000000L));
        assertThat(n).isEqualTo(2); // out + in
        assertThat(tx.calls()).extracting(CapturingTxUpserter.Tx::type)
                .containsExactlyInAnyOrder("CONVERT_OUT","CONVERT_IN");
    }
}
