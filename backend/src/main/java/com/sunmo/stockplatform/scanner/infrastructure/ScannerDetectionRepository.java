package com.sunmo.stockplatform.scanner.infrastructure;

import com.sunmo.stockplatform.scanner.domain.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public interface ScannerDetectionRepository extends JpaRepository<ScannerDetection, Long> {
    List<ScannerDetection> findByTypeOrderByDetectedAtDesc(ScannerType type, Pageable pageable);

    List<ScannerDetection> findAllByOrderByDetectedAtDesc(Pageable pageable);

    List<ScannerDetection> findByTypeAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(ScannerType type,
            Instant from, Pageable pageable);

    List<ScannerDetection> findByDetectedAtGreaterThanEqualOrderByDetectedAtDesc(Instant from, Pageable pageable);

    Optional<ScannerDetection> findById(Long id);

    List<ScannerDetection> findByDetectedAtBetweenOrderByDetectedAtAsc(Instant from, Instant to);

    List<ScannerDetection> findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(
            LocalDate sessionDate, Instant from);
}
