/*
 * 장마감 추천 "실행 기록"(한 번 평가를 돌린 단위, ClosingRecommendationRun)을 DB에서 찾는 레포지토리(DB 창구 역할 인터페이스)예요.
 * 날짜별 최신 실행, 실행 모드(FORWARD 등)별 최신 실행, 요청 키로 실행을 찾을 수 있어요.
 * 장마감 추천 서비스와 스케줄러가 중복 실행 방지와 대표 실행 선택에 사용해요.
 */
package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;

/**
 * ClosingRecommendationRun 엔티티(DB 표 한 줄에 대응하는 자바 객체)를 다루는 JPA 저장소예요.
 * 시험 회차 기록부처럼 "그날 몇 번째 평가였는지, 어떤 방식이었는지"를 찾아 줘요.
 * 메서드 이름만으로 Spring Data가 쿼리를 만들어요.
 * - findFirstByRecommendationDateOrderByIdDesc: 그날 가장 최근 실행 하나(없으면 빈 값).
 * - findFirstByRecommendationDateAndExecutionModeOrderByIdDesc: 그날 특정 모드의 가장 최근 실행 하나.
 * - findByRequestKey: 요청 키(예: official-날짜)로 실행 하나. 같은 요청이 두 번 실행되지 않게 확인할 때 써요.
 * - findByRecommendationDateOrderByIdDesc: 그날 실행 전체, 최신순.
 */
public interface ClosingRecommendationRunRepository extends JpaRepository<ClosingRecommendationRun, Long> {
    Optional<ClosingRecommendationRun> findFirstByRecommendationDateOrderByIdDesc(LocalDate date);
    Optional<ClosingRecommendationRun> findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(
            LocalDate date, String executionMode);
    Optional<ClosingRecommendationRun> findByRequestKey(String key);
    List<ClosingRecommendationRun> findByRecommendationDateOrderByIdDesc(LocalDate date);
}
