package com.sandkev.cryptio;

import com.sandkev.cryptio.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TxRepo extends JpaRepository<Transaction, Long> {
    List<Transaction> findByTsBetween(Instant from, Instant to);
}