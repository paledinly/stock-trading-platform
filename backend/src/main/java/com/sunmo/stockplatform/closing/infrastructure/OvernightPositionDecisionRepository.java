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
     * 하는 일: 특정 날짜의 "대표 실행"에서 추천마다 평가시각이 가장 늦은 판단 한 줄을 순위 순으로 가져와요.
     * 대표 실행은 추천 조회와 똑같이 그날 가장 최근 FORWARD 실행이고, 없으면 그날 가장 최근 실행이에요.
     * 평가시각이 같은 판단이 여러 개면 그중 ID가 가장 큰(가장 나중에 저장된) 것 하나만 골라 추천마다 한 줄이에요.
     * 받는 것: 추천 날짜예요. 돌려주는 것: 판단 목록이에요.
     */
    @Query("""
            select d from OvernightPositionDecision d
            join d.recommendation recommendation
            where recommendation.run.id = coalesce(
                  (select max(a.id) from ClosingRecommendationRun a
                    where a.recommendationDate = :date and a.executionMode = 'FORWARD'),
                  (select max(b.id) from ClosingRecommendationRun b where b.recommendationDate = :date))
              and d.id = (
                  select max(tied.id) from OvernightPositionDecision tied
                  where tied.recommendation = recommendation
                    and tied.evaluatedAt = (
                        select max(latest.evaluatedAt) from OvernightPositionDecision latest
                        where latest.recommendation = recommendation))
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
