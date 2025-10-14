// src/test/java/com/sandkev/cryptio/kraken/KrakenSymbolMapperTest.java
package com.sandkev.cryptio.kraken;

import com.sandkev.cryptio.exchange.kraken.KrakenSymbolMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KrakenSymbolMapperTest {

    private final KrakenSymbolMapper mapper = new KrakenSymbolMapper();

    @Test
    void stripsSuffixAndMapsBtcToXbt() {
        assertThat(mapper.toKrakenBase("BTC")).isEqualTo("XBT");
        assertThat(mapper.toKrakenBase("btc.f")).isEqualTo("XBT");
        assertThat(mapper.toKrakenBase("Ada.F")).isEqualTo("ADA");
        assertThat(mapper.toKrakenBase("ETH")).isEqualTo("ETH");
    }

    @Test
    void producesPreferredQuoteCandidates() {
        List<String> pairs = mapper.candidatePairs("XBT");
        assertThat(pairs).containsExactly("XBTUSDT", "XBTUSD", "XBTEUR", "XBTGBP");
    }
}
