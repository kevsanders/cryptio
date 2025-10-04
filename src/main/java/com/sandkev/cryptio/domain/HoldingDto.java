package com.sandkev.cryptio.domain;


import java.math.BigDecimal;

public record HoldingDto(String asset, BigDecimal quantity, BigDecimal avgCost) {
    static HoldingDto from(Holding h) {
        return new HoldingDto(h.getAsset(), h.getQuantity(), h.getAvgCost());
    }
}

