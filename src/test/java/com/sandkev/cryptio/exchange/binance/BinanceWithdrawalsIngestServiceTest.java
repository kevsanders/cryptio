package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.exchange.binance.testsupport.CapturingTxUpserter;
import com.sandkev.cryptio.exchange.binance.testsupport.FakeBinanceSignedClientFromClasspath;
import com.sandkev.cryptio.exchange.binance.testsupport.InMemoryCheckpointDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceWithdrawalsIngestServiceTest {

    @Test
    void ingestsWithdrawals() {
        var client = new FakeBinanceSignedClientFromClasspath(Map.of(
                "/sapi/v1/capital/withdraw/history", "/binance/withdraw_history.json"
        ));
        var ckpt = new InMemoryCheckpointDao();
        var tx = new CapturingTxUpserter();
        var svc = new BinanceWithdrawalsIngestService(client, ckpt, tx);

        int n = svc.ingest("acct", Instant.ofEpochMilli(1699999000000L));
        assertThat(n).isEqualTo(2);
        assertThat(tx.calls()).extracting(CapturingTxUpserter.Tx::type)
                .containsOnly("WITHDRAW");
    }
}
