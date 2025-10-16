package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.exchange.binance.testsupport.CapturingTxUpserter;
import com.sandkev.cryptio.exchange.binance.testsupport.InMemoryCheckpointDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class BinanceConvertIngestServiceTest {

    @Test
    void ingestsConvertRows() {
        var client = new CapturingTxUpserter.FakeBinanceSignedClientFromClasspath(Map.of(
                "/sapi/v1/convert/tradeFlow", "/binance/convert_tradeFlow.json"
        ));
        var ckpt = new InMemoryCheckpointDao();
        var tx = new CapturingTxUpserter();

        // real instance, then spy it
        BinanceConvertIngestService real = new BinanceConvertIngestService(client, ckpt, tx);
        BinanceConvertIngestService svc  = spy(real);

        // stub only the sleep duration
        doReturn(1L).when(svc).preCallPauseMs();

        int n = svc.ingest("acct", Instant.ofEpochMilli(1699999000000L));
        assertThat(n).isEqualTo(4); // 2 converts -> 2 upserts each
        assertThat(tx.calls()).extracting(CapturingTxUpserter.Tx::externalId)
                .contains("convert:out:123","convert:in:123","convert:out:124","convert:in:124");
    }
}
