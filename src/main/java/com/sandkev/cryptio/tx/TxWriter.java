// src/main/java/com/sandkev/cryptio/tx/TxWriter.java
package com.sandkev.cryptio.tx;

import com.sandkev.cryptio.domain.Tx;

public interface TxWriter {
    int write(Tx tx);

}