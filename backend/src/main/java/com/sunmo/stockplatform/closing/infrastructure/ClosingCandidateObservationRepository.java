package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.ClosingCandidateObservation;
import com.sunmo.stockplatform.closing.domain.OvernightPerformanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.*;

public interface ClosingCandidateObservationRepository extends JpaRepository<ClosingCandidateObservation, Long> {
    Optional<ClosingCandidateObservation> findByRunIdAndStockIdAndCandidateSource(
            Long runId, Long stockId, String candidateSource);
    List<ClosingCandidateObservation> findByRunIdOrderByDispositionAscIdAsc(Long runId);

    /** Runs whose observed session has started but some rows were never re-evaluated after it. */
    @Query("select distinct o.run.id from ClosingCandidateObservation o "
            + "where o.status in :statuses and o.expectedSessionDate <= :sessionDate")
    List<Long> findRunIdsByStatusInAndExpectedSessionDateOnOrBefore(
            @Param("statuses") Collection<OvernightPerformanceStatus> statuses,
            @Param("sessionDate") LocalDate sessionDate);
}
