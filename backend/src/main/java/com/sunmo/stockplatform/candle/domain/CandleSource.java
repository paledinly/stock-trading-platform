/*
 * 저장된 봉이 어디서 왔는지(출처)를 나타내는 값 목록이에요.
 * StockCandle이 출처를 기록하고, 보충 로직은 이 값으로 실시간 확정봉을 덮어쓰지 않도록 판단해요.
 */
package com.sunmo.stockplatform.candle.domain;

/**
 * 봉의 출처 표시예요. 원본 사진과 복원본을 구분하는 라벨과 같아요.
 * REALTIME: 실시간 체결로 직접 집계한 봉, BACKFILL: KIS 과거 분봉·일봉으로 나중에 채운 봉(실시간 당시 수신을 증명하지 못해요).
 */
public enum CandleSource {
    REALTIME,
    BACKFILL
}
