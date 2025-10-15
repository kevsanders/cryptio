package com.sandkev.cryptio.portfolio;

import java.util.Set;

public interface AssetUniverseDao {
    /** All base assets we have seen for an account on an exchange. */
    Set<String> assetsForAccount(String exchange, String accountRef);
}