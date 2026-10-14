/*
 * 추천 종목의 다음 거래일 성과(OvernightPerformance)를 DB에서 찾고 지우는 레포지토리(DB 창구 역할 인터페이스)예요.
 * 날짜별·실행별 성과 조회와, 전략 분석에 쓰는 "공식·완료된 성과만" 기간 조회를 제공해요.
 * 다음 날 성과 서비스, 전략 분석 서비스, 시장 커버리지 서비스가 사용해요.
 */
package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.OvernightPerformance;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.*;

/**
 * OvernightPerformance 엔티티(DB 표 한 줄에 대응하는 자바 객체)를 다루는 JPA 저장소예요.
 * 성적표 보관함처럼 추천 한 건마다 붙은 다음 날 성적표를 날짜·실행·기간 기준으로 꺼내 줘요.
 */
public interface OvernightPerformanceRepository extends JpaRepository<OvernightPerformance, Long> {
    /**
     * 하는 일: 추천 한 건에 연결된 성과를 찾아요.
     * 받는 것: 추천 ID예요. 돌려주는 것: 성과 또는 빈 Optional이에요.
     */
    Optional<OvernightPerformance> findByRecommendationId(Long recommendationId);

    /**
     * 하는 일: 특정 추천 날짜의 모든 추천에 붙은 성과를 지워요.
     * 받는 것: 추천 날짜예요. 돌려주는 것: 없어요.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from OvernightPerformance p
             where p.recommendation.id in (
                 select r.id from ClosingRecommendation r
                  where r.recommendationDate = :date
             )
            """)
    void deleteByRecommendationDate(@Param("date") LocalDate date);

    /**
     * 하는 일: 특정 날짜의 "대표 실행"에 속한 성과를 추천·종목과 함께 순위 순으로 가져와요.
     * 대표 실행은 추천 조회(ClosingRecommendationRepository.findByRecommendationDateOrderByRankAsc)와 똑같이
     * 그날 가장 최근 FORWARD 실행이고, 없으면 그날 가장 최근 실행이에요. 그래서 성과를 계산한 실행과 보여 주는 실행이 같아요.
     * 받는 것: 추천 날짜예요. 돌려주는 것: 순위 오름차순 성과 목록이에요.
     */
    @Query("""
            select p from OvernightPerformance p
              join fetch p.recommendation r
              join fetch r.stock
             where r.run.id = coalesce(
                   (select max(a.id) from ClosingRecommendationRun a
                     where a.recommendationDate = :date and a.executionMode = 'FORWARD'),
                   (select max(b.id) from ClosingRecommendationRun b where b.recommendationDate = :date))
             order by r.rank asc
            """)
    List<OvernightPerformance> findByRecommendationDate(@Param("date") LocalDate date);

    /**
     * 하는 일: 특정 실행의 성과를 추천 순위 순으로 가져와요.
     * 받는 것: 실행 ID예요. 돌려주는 것: 성과 목록이에요.
     */
    List<OvernightPerformance> findByRecommendationRunIdOrderByRecommendationRankAsc(Long runId);

    /**
     * 하는 일: 기간 안의 "공식" 성과만 모아요. 조건은 계산 완료(COMPLETED), 같은 계산 버전,
     * FORWARD 실행, 요청 키가 official-로 시작하는 자동 공식 실행이에요(수동·재생 실행은 제외).
     * 받는 것: 시작일, 종료일(둘 다 포함), 계산 버전이에요. 돌려주는 것: 날짜·순위 순 성과 목록이에요.
     */
    @Query("""
            select p from OvernightPerformance p
              join fetch p.recommendation r
              join fetch r.run run
              join fetch r.stock
             where p.status = com.sunmo.stockplatform.closing.domain.OvernightPerformanceStatus.COMPLETED
               and p.calculationVersion = :version
               and r.recommendationDate between :from and :to
               and run.executionMode = 'FORWARD'
               and run.requestKey like 'official-%'
             order by r.recommendationDate asc, r.rank asc
            """)
    List<OvernightPerformance> findOfficialCompletedBetween(@Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("version") String version);
}
