/*
 * 장마감 후보 관측 기록(추천에 뽑히지 않은 후보 포함)을 DB에서 찾고 저장하는 레포지토리(DB 창구 역할 인터페이스)예요.
 * Spring Data JPA(자바 객체와 DB 표를 이어 주는 기술)가 메서드 이름과 쿼리를 보고 실제 SQL을 만들어 줘요.
 * 후보 관측 서비스와 장마감 스케줄러가 관측 저장·재평가 대상 조회에 사용해요.
 */
package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.ClosingCandidateObservation;
import com.sunmo.stockplatform.closing.domain.OvernightPerformanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.*;

/**
 * ClosingCandidateObservation 엔티티(DB 표 한 줄에 대응하는 자바 객체)를 다루는 저장소예요.
 * 도서관 대출 대장처럼 "어느 실행(run)에서 어떤 종목을 어떤 경로로 관찰했는지"를 찾아 주는 역할이에요.
 */
public interface ClosingCandidateObservationRepository extends JpaRepository<ClosingCandidateObservation, Long> {
    /**
     * 하는 일: 한 실행에서 특정 종목·후보 출처 조합의 관측 기록을 찾아요(중복 저장 방지용으로 보여요).
     * 받는 것: 실행 ID, 종목 ID, 후보 출처 문자열이에요.
     * 돌려주는 것: 있으면 관측 기록, 없으면 빈 Optional이에요.
     */
    Optional<ClosingCandidateObservation> findByRunIdAndStockIdAndCandidateSource(
            Long runId, Long stockId, String candidateSource);
    /**
     * 하는 일: 한 실행의 관측 기록 전체를 처리 결과(disposition) 순, 같으면 ID 순으로 가져와요.
     * 받는 것: 실행 ID예요.
     * 돌려주는 것: 정렬된 관측 기록 목록이에요.
     */
    List<ClosingCandidateObservation> findByRunIdOrderByDispositionAscIdAsc(Long runId);

    /** Runs whose observed session has started but some rows were never re-evaluated after it. */
    /*
     * 하는 일: 관측 대상 거래일이 이미 시작됐는데 아직 결과를 다시 계산하지 않은 행이 남은 실행 ID를 찾아요.
     * 받는 것: 재평가가 필요한 상태 목록, 기준 거래일(이 날짜 이전·당일 대상)이에요.
     * 돌려주는 것: 중복 없는 실행 ID 목록이에요.
     */
    @Query("select distinct o.run.id from ClosingCandidateObservation o "
            + "where o.status in :statuses and o.expectedSessionDate <= :sessionDate")
    List<Long> findRunIdsByStatusInAndExpectedSessionDateOnOrBefore(
            @Param("statuses") Collection<OvernightPerformanceStatus> statuses,
            @Param("sessionDate") LocalDate sessionDate);
}
