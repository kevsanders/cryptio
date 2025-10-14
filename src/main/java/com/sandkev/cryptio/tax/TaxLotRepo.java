package com.sandkev.cryptio.tax;

import com.sandkev.cryptio.domain.TaxLot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxLotRepo extends JpaRepository<TaxLot, Long> {}