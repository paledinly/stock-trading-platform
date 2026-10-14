/*
 * 정밀 실시간 구독(특정 종목의 체결/호가를 실시간으로 받아 보겠다고 신청하는 것)의 이력을 DB에 남기는 파일이에요.
 * 신청됨 → 활성화 / 거절 → 종료 단계를 종목별 세션 기록으로 저장해요.
 * PrecisionSubscriptionAllocator 등 정밀 구독을 다루는 쪽에서 사용해요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.marketwide.domain.PrecisionSubscriptionSession;
import com.sunmo.stockplatform.marketwide.infrastructure.PrecisionSubscriptionSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * 정밀 구독 세션의 상태 변화를 기록하는 서비스예요.
 * 택배 송장 조회처럼 "접수됨 → 배송 중 → 배송 완료/반송" 단계를 하나씩 찍어 두는 기록원과 같아요.
 */
@Service
public class PrecisionSubscriptionHistory {
    private final PrecisionSubscriptionSessionRepository sessions;
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
        sessions.findFirstByStockCodeAndStatusOrderByRequestedAtDesc(code, PrecisionSubscriptionSession.Status.ACTIVE)
                .ifPresent(value -> value.end(at, reason));
    }
}
