package com.sandkev.cryptio.tx;

import com.sandkev.cryptio.domain.KrakenTxEntity;

import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

@Repository
public interface KrakenTxRepository extends JpaRepository<KrakenTxEntity, String>, JpaSpecificationExecutor<KrakenTxEntity> {
    boolean existsByTxid(String txid);
}
