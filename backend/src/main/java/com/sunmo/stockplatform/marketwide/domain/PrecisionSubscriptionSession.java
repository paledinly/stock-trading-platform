/*
 * 정밀 감시용 실시간 구독(KIS 웹소켓으로 특정 종목 체결을 계속 받아 보겠다고 신청하는 것) 한 건의 이력을 DB에 남겨요.
 * 신청 → 활성 → 종료/거절 흐름과 각 시각, 종료 사유를 기록해요.
 * PrecisionSubscriptionHistory가 갱신하고, MarketCoverageService와 DailyCandleBackfillService가 읽어 가요.
 */
package com.sunmo.stockplatform.marketwide.domain;

import jakarta.persistence.*;
import java.time.*;

/**
 * 한 종목을 정밀 감시 구독한 "한 번의 구간"을 기록하는 엔티티(DB 표의 한 줄과 짝을 이루는 객체)예요.
 * 도서관 열람실 좌석 이용 기록처럼 "신청 시각, 실제 착석 시각, 퇴실 시각과 사유"를 남긴다고 생각하면 돼요.
 */
@Entity
@Table(name = "precision_subscription_session")
public class PrecisionSubscriptionSession {
    /** 구독 상태예요: 신청함(REQUESTED), 실제 수신 중(ACTIVE), 정상 종료(ENDED), 거절됨(REJECTED). */
    public enum Status { REQUESTED, ACTIVE, ENDED, REJECTED }
    /** 거래일은 한국 시간 기준으로 정해요. */
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_date", nullable = false) private LocalDate sessionDate;
    @Column(name = "stock_code", nullable = false, length = 12) private String stockCode;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "activated_at") private Instant activatedAt;
    @Column(name = "ended_at") private Instant endedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "end_reason", length = 40) private String endReason;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    /** JPA(DB 기록을 객체로 바꿔 주는 도구)가 DB에서 읽어 올 때만 쓰는 빈 생성자예요. */
    protected PrecisionSubscriptionSession() {}
    /**
     * 하는 일: 새 구독 이력을 만들어요. 거래일은 신청 시각의 한국 날짜로 정해요.
     * 받는 것: 종목 코드, 신청 시각, 바로 활성인지 여부(true면 신청 시각을 활성 시각으로도 써요).
     * 돌려주는 것: 새 PrecisionSubscriptionSession(생성자라 따로 반환값은 없어요).
     */
    public PrecisionSubscriptionSession(String stockCode, Instant requestedAt, boolean active) {
        this.stockCode = stockCode;
        this.requestedAt = requestedAt;
        this.sessionDate = requestedAt.atZone(MARKET_ZONE).toLocalDate();
        this.status = active ? Status.ACTIVE : Status.REQUESTED;
        this.activatedAt = active ? requestedAt : null;
    }
    /*
     * 상태 변경 메서드들이에요(돌려주는 것은 없어요).
     * - activate: 받은 시각에 실제 수신이 시작됐다고 표시해요.
     * - reject: 받은 시각과 사유로 구독이 거절됐다고 표시해요.
     * - end: 받은 시각과 사유로 구독이 끝났다고 표시해요.
     * - trim: 사유 글자를 DB 칸 길이(40자)에 맞게 잘라요.
     */
    public void activate(Instant at) { activatedAt = at; status = Status.ACTIVE; }
    public void reject(Instant at, String reason) { endedAt = at; endReason = trim(reason); status = Status.REJECTED; }
    public void end(Instant at, String reason) { endedAt = at; endReason = trim(reason); status = Status.ENDED; }
    private String trim(String value) { return value == null ? null : value.substring(0, Math.min(40, value.length())); }
    /*
     * prePersist/preUpdate: DB에 처음 저장되거나 수정되기 직전에 생성·수정 시각을 자동으로 채워요.
     * 아래 get 메서드들은 저장된 값을 그대로 돌려주는 읽기 전용 메서드예요.
     */
    @PrePersist void prePersist() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public LocalDate getSessionDate() { return sessionDate; }
    public String getStockCode() { return stockCode; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Status getStatus() { return status; }
    public String getEndReason() { return endReason; }
}
