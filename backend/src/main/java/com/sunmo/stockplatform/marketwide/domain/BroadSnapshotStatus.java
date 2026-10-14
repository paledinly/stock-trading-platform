/*
 * BROAD 스냅샷(특정 순간에 찍어 둔 종목 기록)의 수집 결과 상태를 정해요.
 * 시세를 제대로 모았으면 COLLECTED, 시세가 없거나 핵심 값이 빠졌으면 QUOTE_FAILED예요.
 * MarketBroadSnapshot과 커버리지 집계가 이 값을 사용해요.
 */
package com.sunmo.stockplatform.marketwide.domain;

/**
 * BROAD 스냅샷의 시세 수집이 성공했는지 나타내는 상태값이에요.
 * 택배 송장에 "배송 완료(COLLECTED)" 또는 "배송 실패(QUOTE_FAILED)" 도장을 찍는 것과 같아요.
 */
public enum BroadSnapshotStatus {
    COLLECTED, QUOTE_FAILED
}
