/*
 * 정해진 시각마다 도는 전체 시장 스캔 한 회차의 실행 기록을 DB에 남기는 엔티티(DB 표의 한 줄과 짝을 이루는 객체)예요.
 * 예정 시각, 시작·종료 시각, 성공/실패, 스캔 종목 수와 후보 수를 기록해요. 예정 시각 하나에 기록은 한 줄뿐이에요.
 * MarketWideScanCoordinator가 만들고 갱신하며, MarketCoverageService가 커버리지 집계에 읽어 가요.
 */
package com.sunmo.stockplatform.marketwide.domain;

import jakarta.persistence.*;
import java.time.*;

/**
 * 시장 스캔 한 회차가 언제 시작해서 어떻게 끝났는지 적는 기록이에요.
 * 버스 운행 일지에 "몇 시 차가 출발했고, 도착했는지 혹은 고장 났는지"를 한 줄씩 적는 것과 같아요.
 */
@Entity
@Table(name = "market_wide_scan_run", uniqueConstraints = @UniqueConstraint(
        name = "uk_market_wide_scan_run_schedule", columnNames = "scheduled_for"))
public class MarketWideScanRun {
    /** 회차 상태예요: 실행 중(RUNNING), 완료(COMPLETED), 실패(FAILED). */
    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;
    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status;
    @Column(name = "scanned_count", nullable = false)
    private int scannedCount;
    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;
    @Column(nullable = false)
    private boolean fallback;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA(DB 기록을 객체로 바꿔 주는 도구)가 DB에서 읽어 올 때만 쓰는 빈 생성자예요. */
    protected MarketWideScanRun() {}

    /**
     * 하는 일: 실행 중(RUNNING) 상태의 새 회차 기록을 만들어요.
     * 받는 것: 거래일, 예정 시각, 실제 시작 시각.
     * 돌려주는 것: 새 MarketWideScanRun(생성자라 따로 반환값은 없어요).
     */
    public MarketWideScanRun(LocalDate date, Instant scheduledFor, Instant startedAt) {
        this.sessionDate = date;
        this.scheduledFor = scheduledFor;
        this.startedAt = startedAt;
        this.status = Status.RUNNING;
    }

    /**
     * 하는 일: 회차를 완료(COMPLETED)로 바꾸고 결과 수치를 적어요. 예전 오류 메시지는 지워요.
     * 받는 것: 완료 시각, 스캔한 종목 수, 후보 수, 대체 경로(fallback) 사용 여부.
     * 돌려주는 것: 없음.
     */
    public void complete(Instant at, int scanned, int candidates, boolean fallback) {
        completedAt = at;
        scannedCount = scanned;
        candidateCount = candidates;
        this.fallback = fallback;
        status = Status.COMPLETED;
        errorMessage = null;
    }

    /**
     * 하는 일: 회차를 실패(FAILED)로 바꾸고 오류 메시지를 남겨요.
     * 받는 것: 종료 시각, 오류 메시지.
     * 돌려주는 것: 없음.
     */
    public void fail(Instant at, String error) {
        completedAt = at;
        status = Status.FAILED;
        errorMessage = error;
    }

    /**
     * 하는 일: 같은 예정 시각의 회차를 다시 돌릴 때 상태를 실행 중으로 되돌리고 종료 정보를 비워요.
     * 이전 회차의 스캔 수·후보 수는 다음 complete 때 덮어써지는 것으로 보여요.
     * 받는 것: 새 시작 시각.
     * 돌려주는 것: 없음.
     */
    public void restart(Instant at) {
        startedAt = at;
        completedAt = null;
        status = Status.RUNNING;
        errorMessage = null;
    }

    /*
     * prePersist/preUpdate: DB에 처음 저장되거나 수정되기 직전에 생성·수정 시각을 자동으로 채워요.
     * 아래 get/is 메서드들은 저장된 값을 그대로 돌려주는 읽기 전용 메서드예요.
     */
    @PrePersist void prePersist() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
    public Status getStatus() { return status; }
    public LocalDate getSessionDate() { return sessionDate; }
    public Instant getScheduledFor() { return scheduledFor; }
    public int getScannedCount() { return scannedCount; }
    public int getCandidateCount() { return candidateCount; }
    public boolean isFallback() { return fallback; }
    public String getErrorMessage() { return errorMessage; }
}
