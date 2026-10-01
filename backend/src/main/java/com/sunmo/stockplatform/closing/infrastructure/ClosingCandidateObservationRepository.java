package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.ClosingCandidateObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ClosingCandidateObservationRepository extends JpaRepository<ClosingCandidateObservation, Long> {
    Optional<ClosingCandidateObservation> findByRunIdAndStockIdAndCandidateSource(
            Long runId, Long stockId, String candidateSource);
    List<ClosingCandidateObservation> findByRunIdOrderByDispositionAscIdAsc(Long runId);
}
