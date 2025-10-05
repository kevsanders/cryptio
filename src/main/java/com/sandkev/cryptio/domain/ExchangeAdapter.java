package com.sandkev.cryptio.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ExchangeAdapter {
    String name();
    List<Tx> fetchRecentTrades(Instant since);
    Map<String, BigDecimal> fetchBalances();
}
