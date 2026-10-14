/*
 * 전체 시장 스캔(BROAD)에서 남긴 종목 기록(스냅샷, 특정 순간에 찍어 둔 사진 같은 기록)의 데이터 품질 등급을 정해요.
 * 가격·등락률·거래량·거래대금이 모두 있으면 BROAD_C, 하나라도 빠지면 INSUFFICIENT예요.
 * MarketBroadSnapshot과 커버리지 집계(MarketCoverageService)가 이 값을 사용해요.
 */
package com.sunmo.stockplatform.marketwide.domain;

/**
 * BROAD 스냅샷이 분석에 쓸 만큼 데이터가 갖춰졌는지 나타내는 등급이에요.
 * 답안지를 걷을 때 "다 채웠음(BROAD_C)"과 "빈칸 있음(INSUFFICIENT)" 도장만 먼저 찍어 두는 것과 같아요.
 */
public enum BroadSnapshotQuality {
    BROAD_C, INSUFFICIENT
}
