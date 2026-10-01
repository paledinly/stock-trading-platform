package com.sunmo.stockplatform.intraday;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;
import java.util.List;

public interface IntradayInputRepository extends JpaRepository<IntradayInputEntity, Long> {
    List<IntradayInputEntity> findBySessionDateAndIdGreaterThanOrderById(LocalDate date, Long after, Pageable page);
    long countBySessionDate(LocalDate date);
}
