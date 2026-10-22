/*
 * 정밀 실시간 구독(특정 종목의 체결/호가를 실시간으로 받아 보겠다고 신청하는 것)의 이력을 DB에 남기는 파일이에요.
 * 신청됨 → 활성화 / 거절 → 종료 단계를 종목별 세션 기록으로 저장해요.
 * PrecisionSubscriptionAllocator 등 정밀 구독을 다루는 쪽에서 사용해요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.marketwide.domain.PrecisionSubscriptionSession;
import com.sunmo.stockplatform.marketwide.infrastructure.PrecisionSubscriptionSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

/**
 * 정밀 구독 세션의 상태 변화를 기록하는 서비스예요.
 * 택배 송장 조회처럼 "접수됨 → 배송 중 → 배송 완료/반송" 단계를 하나씩 찍어 두는 기록원과 같아요.
 */
@Service
public class PrecisionSubscriptionHistory {
    private static final Logger log = LoggerFactory.getLogger(PrecisionSubscriptionHistory.class);
    private final PrecisionSubscriptionSessionRepository sessions;
    // 이 프로세스가 만들어진 시각이에요. 이보다 먼저 신청된 열린 세션은 이전 프로세스가 남긴 기록이에요.
    private final Instant processStart = Instant.now();
    /**
     * 하는 일: 세션 레포지토리(DB에 읽고 쓰는 창구)를 받아 둬요.
     * 받는 것: 정밀 구독 세션 레포지토리.
     */
    public PrecisionSubscriptionHistory(PrecisionSubscriptionSessionRepository sessions) { this.sessions = sessions; }

    /**
     * 하는 일: 구독을 신청했다는 새 세션 기록을 만들어요. 트랜잭션(한 묶음으로 처리되는 DB 작업) 안에서 저장해요.
     * 받는 것: 종목코드, 신청 시각, 이미 구독 중이었는지 여부.
     * 돌려주는 것: 없음.
     */
    @Transactional
    public void requested(String code, Instant at, boolean alreadySubscribed) {
        sessions.save(new PrecisionSubscriptionSession(code, at, alreadySubscribed));
    }
    /**
     * 하는 일: 가장 최근의 "신청됨" 세션을 찾아 활성화 상태로 바꿔요.
     * 받는 것: 종목코드, 활성화 시각.
     * 돌려주는 것: 없음. 해당 세션이 없으면 아무것도 하지 않아요.
     */
    @Transactional
    public void activated(String code, Instant at) {
        sessions.findFirstByStockCodeAndStatusOrderByRequestedAtDesc(code, PrecisionSubscriptionSession.Status.REQUESTED)
                .ifPresent(value -> value.activate(at));
    }
    /**
     * 하는 일: 가장 최근의 "신청됨" 세션을 거절 상태로 바꾸고 사유를 남겨요.
     * 받는 것: 종목코드, 거절 시각, 거절 사유.
     * 돌려주는 것: 없음. 해당 세션이 없으면 아무것도 하지 않아요.
     */
    @Transactional
    public void rejected(String code, Instant at, String reason) {
        sessions.findFirstByStockCodeAndStatusOrderByRequestedAtDesc(code, PrecisionSubscriptionSession.Status.REQUESTED)
                .ifPresent(value -> value.reject(at, reason));
    }
    /**
     * 하는 일: 가장 최근의 "활성" 세션을 종료 상태로 바꾸고 사유를 남겨요.
     * 받는 것: 종목코드, 종료 시각, 종료 사유.
     * 돌려주는 것: 없음. 활성 세션이 없으면 아무것도 하지 않아요.
     */
    @Transactional
    public void ended(String code, Instant at, String reason) {
        endLatestActive(code, at, reason);
    }

    /**
     * 하는 일: 재시작 없이 날짜가 바뀌어 이어진 정밀 구독을 오늘 날짜 세션으로 다시 기록해요.
     * 전날 활성 세션은 DAY_ROLLOVER로 닫고, 오늘 날짜의 활성 세션을 새로 만들어요(이미 구독 중이므로 바로 활성).
     * 받는 것: 종목코드, 기록 시각(그날 첫 배정 조정 시각).
     */
    @Transactional
    public void rolledOver(String code, Instant at) {
        endLatestActive(code, at, "DAY_ROLLOVER");
        sessions.save(new PrecisionSubscriptionSession(code, at, true));
    }

    /**
     * 하는 일: 앱 시작 직후, 이전 프로세스가 강제 종료되며 열어 둔 채 남긴 신청·활성 세션을 PROCESS_RESTART로 닫아요.
     * 이 프로세스 시작 전에 신청된 것만 닫으므로, 시작 직후 새로 신청한 세션은 건드리지 않아요.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void closeLeftovers() {
        List<PrecisionSubscriptionSession> open = sessions.findByStatusInAndRequestedAtBefore(
                List.of(PrecisionSubscriptionSession.Status.REQUESTED, PrecisionSubscriptionSession.Status.ACTIVE),
                processStart);
        open.forEach(session -> session.end(processStart, "PROCESS_RESTART"));
        if (!open.isEmpty())
            log.info("Closed {} precision subscription sessions left open by the previous process", open.size());
    }

    private void endLatestActive(String code, Instant at, String reason) {
        sessions.findFirstByStockCodeAndStatusOrderByRequestedAtDesc(code, PrecisionSubscriptionSession.Status.ACTIVE)
                .ifPresent(value -> value.end(at, reason));
    }
}
