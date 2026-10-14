/*
 * 추천 종목을 다음 날 "계속 보유할지/정리할지" 판단한 기록(OvernightPositionDecision)을 DB에서 찾고 지우는 레포지토리(DB 창구 역할 인터페이스)예요.
 * 한 추천에 판단이 여러 번 쌓일 수 있어서, 날짜별·실행별로 "가장 최근 판단"만 골라 주는 쿼리를 제공해요.
 * 보유 판단 서비스(OvernightPositionDecisionService)가 사용해요.
 */
package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.OvernightPositionDecision;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * OvernightPositionDecision 엔티티(DB 표 한 줄에 대응하는 자바 객체)를 다루는 JPA 저장소예요.
 * 진료 기록부에서 환자마다 가장 최근 진단만 뽑아 보는 것처럼, 추천마다 최신 판단 한 줄을 꺼내 줘요.
 */
public interface OvernightPositionDecisionRepository extends JpaRepository<OvernightPositionDecision, Long> {
    /**
     * 하는 일: 특정 추천 날짜의 모든 추천에 붙은 보유 판단 기록을 지워요.
     * 받는 것: 추천 날짜예요. 돌려주는 것: 없어요.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from OvernightPositionDecision d
             where d.recommendation.id in (
                 select r.id from ClosingRecommendation r
                  where r.recommendationDate = :date
             )
            """)
    void deleteByRecommendationDate(@Param("date") LocalDate date);

    /**
     * 하는 일: 특정 날짜의 가장 최근 실행(모드 무관)에서 추천마다 평가시각이 가장 늦은 판단을 순위 순으로 가져와요.
     * 받는 것: 추천 날짜예요. 돌려주는 것: 판단 목록이에요.
     * ⚠️ 확인 필요: 추천 조회는 FORWARD 실행을 우선하지만 여기는 max(id) 실행을 써서 서로 다른 실행을 볼 수 있어요.
     * 또 같은 evaluatedAt 판단이 두 개면 한 추천에 두 줄이 나올 수 있어요(실행별 조회는 max(id)라 한 줄).
     */
    @Query("""
            select d from OvernightPositionDecision d
            join d.recommendation recommendation
            where recommendation.run.id =
              (select max(a.id) from ClosingRecommendationRun a where a.recommendationDate = :date)
              and d.evaluatedAt = (
                  select max(latest.evaluatedAt) from OvernightPositionDecision latest
                  where latest.recommendation = recommendation
              )
            order by recommendation.rank asc
            """)
    List<OvernightPositionDecision> findLatestByRecommendationDate(@Param("date") LocalDate date);

    /**
     * 하는 일: 특정 실행에서 추천마다 ID가 가장 큰(가장 나중에 저장된) 판단을 순위 순으로 가져와요.
     * 받는 것: 실행 ID예요. 돌려주는 것: 판단 목록이에요.
     */
    @Query("""
            select d from OvernightPositionDecision d join d.recommendation r
            where r.run.id = :runId and d.id =
              (select max(latest.id) from OvernightPositionDecision latest where latest.recommendation = r)
            order by r.rank asc
            """)
    List<OvernightPositionDecision> findLatestByRunId(@Param("runId") Long runId);
}
