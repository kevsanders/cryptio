package com.sandkev.cryptio;

import com.sandkev.cryptio.domain.Tx;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TxRepo extends JpaRepository<Tx, Long> {
    List<Tx> findByTsBetween(Instant from, Instant to);
}