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

    @Query("""
        select t from Tx t
         where (:asset is null or UPPER(t.asset) = UPPER(:asset))
           and (:exchange is null or UPPER(t.exchange) = UPPER(:exchange))
           and (:account is null or t.accountRef = :account)
         order by t.ts desc
    """)
    List<Tx> findFiltered(@Param("asset") String asset,
                          @Param("exchange") String exchange,
                          @Param("account") String account);


//    @Query("select t from Tx t where t.timestamp between :start and :end")
//    List<Tx> findByTsBetween(@Param("start") Instant start,
//                             @Param("end") Instant end);
}
