/*
 * 장마감 추천을 한 번 돌린 "실행(run)" 기록을 나타내는 엔티티(DB 표 한 줄에 대응하는 자바 객체)예요.
 * 언제·어떤 모드(FORWARD 실시간/REPLAY 재생 등)·어떤 설정으로 평가했는지와 그때의 응답 전체(JSON)를 보관해요.
 * 장마감 추천 서비스가 만들고 완료 처리하며, 추천·후보 관찰·성과 기록이 이 실행에 연결돼요.
 */
package com.sunmo.stockplatform.closing.domain;

import jakarta.persistence.*;
import java.time.*;

/** Immutable generation audit; observation candidates never enter overnight position tracking. */
/*
 * (생성 감사 기록이에요. 관찰 후보는 다음 날 보유 판단 추적에 들어가지 않아요.)
 * 엄밀히는 complete()/recordDataVersion()으로 완료 응답과 데이터 버전을 한 번 채우는 것 외에는 바꾸지 않는 기록이에요.
 * 비유하면 시험 회차 기록지예요. 시험 날짜·방식·채점 기준을 적고, 시험이 끝나면 답안 사본을 붙여 보관해요.
 */
@Entity
@Table(name = "closing_recommendation_run")
public class ClosingRecommendationRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "recommendation_date", nullable = false)
    private LocalDate recommendationDate;
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
    @Column(name = "strategy_version", nullable = false, length = 40)
    private String strategyVersion;
    @Column(name = "response_snapshot", nullable = false, columnDefinition = "text")
    private String responseSnapshot;
    @Column(name = "execution_mode", nullable = false, length = 20)
    private String executionMode = "LEGACY";
    @Column(name = "evaluated_as_of")
    private Instant evaluatedAsOf;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "settings_snapshot", nullable = false, columnDefinition = "text")
    private String settingsSnapshot = "{}";
    @Column(name = "settings_hash", length = 64)
    private String settingsHash;
    @Column(name = "data_version", nullable = false, length = 40)
    private String dataVersion = "legacy-unversioned";
    @Column(name = "request_key", unique = true, length = 100)
    private String requestKey;
    /** JPA가 DB에서 읽어 객체를 만들 때만 쓰는 빈 생성자예요. */
    protected ClosingRecommendationRun() { }
    /**
     * 하는 일: 기본(LEGACY 모드) 실행 기록을 만들어요.
     * 받는 것: 추천 날짜, 생성 시각, 전략 버전, 응답 스냅샷(JSON 문자열)이에요. 돌려주는 것: 실행 객체예요.
     */
    public ClosingRecommendationRun(LocalDate date, Instant at, String version, String snapshot) {
        recommendationDate = date;
        generatedAt = at;
        strategyVersion = version;
        responseSnapshot = snapshot;
    }
    /** 저장된 응답 스냅샷(JSON)을 돌려줘요. */
    public String getResponseSnapshot() { return responseSnapshot; }
    /**
     * 하는 일: 실행 모드·평가 기준시각·설정 스냅샷과 해시·요청 키를 갖춘 실행 기록을 만들어요. 응답은 아직 비어 있어요("{}").
     * 받는 것: 추천 날짜, 생성 시각, 전략 버전, 모드, 평가 기준시각(as-of), 설정 JSON, 설정 해시, 요청 키(중복 실행 방지용)예요.
     * 돌려주는 것: 실행 객체예요(데이터 버전은 stored-market-asof-v1).
     */
    public ClosingRecommendationRun(LocalDate date, Instant at, String version, String mode, Instant asOf,
            String settings, String hash, String key) {
        this(date, at, version, "{}");
        executionMode = mode;
        evaluatedAsOf = asOf;
        settingsSnapshot = settings;
        settingsHash = hash;
        requestKey = key;
        dataVersion = "stored-market-asof-v1";
    }
    /** 하는 일: 평가가 끝나면 최종 응답 JSON과 완료 시각을 기록해요. 받는 것: 응답 JSON, 완료 시각. 돌려주는 것: 없어요. */
    public void complete(String response, Instant at) { responseSnapshot = response; completedAt = at; }
    /** 하는 일: 평가에 쓴 데이터 버전 표식을 기록해요. 받는 것: 버전 문자열. 돌려주는 것: 없어요. */
    public void recordDataVersion(String version) { dataVersion = version; }
    /** 아래는 저장된 값을 그대로 돌려주는 조회용 getter들이에요. */
    public Long getId() { return id; }
    public LocalDate getRecommendationDate() { return recommendationDate; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getExecutionMode() { return executionMode; }
    public Instant getEvaluatedAsOf() { return evaluatedAsOf; }
    public Instant getCompletedAt() { return completedAt; }
    public String getSettingsSnapshot() { return settingsSnapshot; }
    public String getSettingsHash() { return settingsHash; }
    public String getDataVersion() { return dataVersion; }
    public String getRequestKey() { return requestKey; }
}
