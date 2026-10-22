/*
 * 정밀 구독 이력(PrecisionSubscriptionSession)을 DB에서 읽고 쓰는 레포지토리(DB 창고 담당자)예요.
 * 종목의 최신 이력, 거래일 전체 이력, 거래일·상태별 이력을 찾아 줘요.
 * PrecisionSubscriptionHistory, MarketCoverageService, DailyCandleBackfillService가 사용해요.
 */
package com.sunmo.stockplatform.marketwide.infrastructure;

import com.sunmo.stockplatform.marketwide.domain.PrecisionSubscriptionSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.*;

/**
 * 정밀 구독(실시간 체결을 계속 받아 보는 신청) 이력을 보관하고 찾아 주는 창구예요.
 * 열람실 좌석 이용 대장에서 "이 사람의 가장 최근 기록"이나 "오늘 이용 기록 전체"를 찾아 주는 직원과 같아요.
 */
public interface PrecisionSubscriptionSessionRepository extends JpaRepository<PrecisionSubscriptionSession, Long> {
    /**
     * 하는 일: 종목 코드와 상태가 맞는 이력 중 가장 최근에 신청된 것 하나를 찾아요.
     * 받는 것: 종목 코드, 상태. 돌려주는 것: 찾은 이력(없으면 빈 Optional).
     */
    Optional<PrecisionSubscriptionSession> findFirstByStockCodeAndStatusOrderByRequestedAtDesc(
            String stockCode, PrecisionSubscriptionSession.Status status);
    /**
     * 하는 일: 거래일의 모든 구독 이력을 신청 시각 오름차순으로 가져와요.
     * 받는 것: 거래일. 돌려주는 것: 이력 목록.
     */
    List<PrecisionSubscriptionSession> findBySessionDateOrderByRequestedAtAsc(LocalDate date);
    /**
     * 하는 일: 거래일과 상태가 맞는 구독 이력을 신청 시각 오름차순으로 가져와요.
     * 받는 것: 거래일, 상태. 돌려주는 것: 이력 목록.
     */
    List<PrecisionSubscriptionSession> findBySessionDateAndStatusOrderByRequestedAtAsc(
            LocalDate date, PrecisionSubscriptionSession.Status status);
    /** 하는 일: 주어진 상태이면서 기준 시각 전에 신청된 세션을 모두 찾아요(재시작 전 프로세스가 남긴 기록 정리용). */
    List<PrecisionSubscriptionSession> findByStatusInAndRequestedAtBefore(
            Collection<PrecisionSubscriptionSession.Status> statuses, java.time.Instant before);
}
