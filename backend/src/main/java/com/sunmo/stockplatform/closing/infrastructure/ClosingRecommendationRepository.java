/*
 * 장마감 추천 종목(ClosingRecommendation)을 DB에서 찾고 지우는 레포지토리(DB 창구 역할 인터페이스)예요.
 * 날짜별·실행별 추천 목록 조회, 추천 종목 코드 목록 조회, 성과 계산 중 동시 수정을 막는 잠금 조회를 제공해요.
 * 장마감 추천 서비스, 다음 날 성과 서비스, 보유 판단 서비스, 스케줄러, 시장 커버리지 서비스가 사용해요.
 */
package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * ClosingRecommendation 엔티티(DB 표 한 줄에 대응하는 자바 객체)를 다루는 JPA(자바 객체와 DB 표를 이어 주는 기술) 저장소예요.
 * 날짜별 추천 명단을 보관하는 서랍장처럼, 같은 날 명단이 여러 장이면 공식(FORWARD) 명단을 먼저 꺼내 줘요.
 */
public interface ClosingRecommendationRepository extends JpaRepository<ClosingRecommendation, Long> {
    /**
     * 하는 일: 특정 추천 날짜의 추천 종목을 모두 지워요.
     * 받는 것: 추천 날짜예요.
     * 돌려주는 것: 없어요. 지운 뒤 영속성 컨텍스트(JPA 메모리 캐시)를 비워 오래된 객체가 남지 않게 해요.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ClosingRecommendation r where r.recommendationDate = :date")
    void deleteByRecommendationDate(@Param("date") LocalDate recommendationDate);

    /**
     * 하는 일: 특정 날짜의 "대표 실행" 하나를 골라 그 실행의 추천 종목을 순위 순으로 가져와요.
     * 대표 실행은 그날 가장 최근 FORWARD(실시간 공식) 실행이고, 없으면 그날 가장 최근 실행이에요.
     * 받는 것: 추천 날짜예요. 돌려주는 것: 순위 오름차순 추천 목록이에요.
     */
    @Query(value = """
            select r.* from closing_recommendation r
             where r.run_id = coalesce(
                 (select max(a.id) from closing_recommendation_run a
                   where a.recommendation_date = :date and a.execution_mode = 'FORWARD'),
                 (select max(a.id) from closing_recommendation_run a
                   where a.recommendation_date = :date))
             order by r.rank_no asc
            """, nativeQuery = true)
    List<ClosingRecommendation> findByRecommendationDateOrderByRankAsc(@Param("date") LocalDate recommendationDate);

    /**
     * 하는 일: 특정 실행의 추천 종목을 순위 순으로 가져와요.
     * 받는 것: 실행 ID예요. 돌려주는 것: 순위 오름차순 추천 목록이에요.
     */
    List<ClosingRecommendation> findByRunIdOrderByRankAsc(Long runId);

    /**
     * 하는 일: 특정 실행의 추천 종목 코드만 순위 순으로 가져와요(실시간 구독 대상 계산용).
     * 받는 것: 실행 ID예요. 돌려주는 것: 종목 코드 목록이에요.
     */
    @Query("""
            select stock.stockCode from ClosingRecommendation recommendation
              join recommendation.stock stock
             where recommendation.run.id = :runId
             order by recommendation.rank asc
            """)
    List<String> findStockCodesByRunId(@Param("runId") Long runId);

    /**
     * 하는 일: 추천 한 건을 DB 쓰기 잠금(PESSIMISTIC_WRITE)을 걸고 읽어요.
     * 같은 추천의 성과를 두 작업이 동시에 고치지 않게 하려는 것으로 보여요. 트랜잭션(한 묶음 DB 작업) 안에서 써야 해요.
     * 받는 것: 추천 ID예요. 돌려주는 것: 잠긴 추천 또는 빈 Optional이에요.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ClosingRecommendation r where r.id = :id")
    java.util.Optional<ClosingRecommendation> findLockedById(@Param("id") Long id);
}
