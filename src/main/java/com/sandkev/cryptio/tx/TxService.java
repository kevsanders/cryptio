package com.sandkev.cryptio.tx;

// src/main/java/com/sandkev/cryptio/tx/TxService.java
//package com.sandkev.cryptio.tx;

import com.sandkev.cryptio.domain.Tx;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TxService {
    private final TxRepository repo;
    public TxService(TxRepository repo) { this.repo = repo; }

    public List<Tx> list(String asset, String exchange, String account) {
        return repo.findFiltered(emptyToNull(asset), emptyToNull(exchange), emptyToNull(account));
    }
    private static String emptyToNull(String s){ return (s==null||s.isBlank())?null:s; }
}
