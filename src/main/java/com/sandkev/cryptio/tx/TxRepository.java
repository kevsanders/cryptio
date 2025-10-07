package com.sandkev.cryptio.tx;

// src/main/java/com/sandkev/cryptio/tx/TxRepository.java
//package com.sandkev.cryptio.tx;

import com.sandkev.cryptio.domain.Tx;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TxRepository extends JpaRepository<Tx, Long> {

    // --- Existing 3-arg method (kept for backward compatibility) ---
    @Query("""
        select t from Tx t
         where (:asset    is null or upper(t.asset) = upper(:asset))
           and (:exchange is null or lower(t.exchange) = lower(:exchange))
           and (:account  is null or t.accountRef = :account)
         order by t.ts desc
    """)
    List<Tx> findFiltered(
            @Param("asset") String asset,
            @Param("exchange") String exchange,
            @Param("account") String account
    );

    // --- New 6-arg overload for UI filtering ---
    @Query("""
        select t from Tx t
         where (:asset    is null or upper(t.asset) = upper(:asset))
           and (:exchange is null or lower(t.exchange) = lower(:exchange))
           and (:account  is null or t.accountRef = :account)
           and (:type     is null or upper(t.type) = upper(:type))
           and (:fromTs   is null or t.ts >= :fromTs)
           and (:toTs     is null or t.ts <  :toTs)
         order by t.ts desc
    """)
    List<Tx> findFiltered(
            @Param("asset") String asset,
            @Param("exchange") String exchange,
            @Param("account") String account,
            @Param("type") String type,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs
    );
}
