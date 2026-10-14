/*
 * 처리를 마친 실시간 틱(체결 한 건) 하나를 다른 기능에 알리는 스프링 이벤트 모양이에요.
 * MarketDataService가 발행하고, 장마감 추천·정밀 후보·연구용 수집기 같은 리스너들이 받아서 써요.
 */
package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import java.time.Instant;

/**
 * Shared observation; source time and gateway receipt time have different meanings.
 * 틱 원본, 그 시점의 Feature 계산값, 서버 수신시각, 데이터 손실 표시를 한 장에 묶은 관측 기록이에요.
 * 원천 체결시각(tick.occurredAt)과 서버 수신시각(receivedAt)은 뜻이 달라요.
 * dataLoss가 true면 이 틱 앞에서 큐 넘침·재연결 등으로 연속성이 끊겼다는 뜻이에요.
 */
public record ObservedMarketTick(MarketTick tick, MarketFeatureSnapshot feature, Instant receivedAt, boolean dataLoss) {
    /**
     * 하는 일: 데이터 손실이 없는 관측을 만들어요.
     * 받는 것: 틱, Feature, 수신시각. 돌려주는 것: dataLoss=false인 관측 기록.
     */
    public ObservedMarketTick(MarketTick tick, MarketFeatureSnapshot feature, Instant receivedAt) {
        this(tick, feature, receivedAt, false);
    }
}
