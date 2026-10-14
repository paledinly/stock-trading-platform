/*
 * 스캐너가 찾는 패턴 종류 9가지를 정의한 파일이에요.
 * 설정, 감지 기록, 평가기, 이벤트 이름에서 써요.
 */
package com.sunmo.stockplatform.scanner.domain;

/**
 * 스캐너 패턴 종류 목록이에요.
 * 공구함 칸마다 붙은 이름표처럼, 어떤 조건으로 종목을 찾을지 종류를 구분해 줘요.
 */
public enum ScannerType {
    // 거래량 배수가 기준 이상
    VOLUME,
    // 변화율이 기준 이상
    PRICE_RISE,
    // 거래량 배수와 변화율 둘 다 기준 이상
    MOMENTUM,
    // 거래량 배수 기준(현재 판정은 VOLUME과 같고 기본 기준값만 달라요)
    VOLUME_BREAKOUT,
    // 5분 거래대금 배수가 기준 이상
    TURNOVER_BREAKOUT,
    // 앞 6봉 최고가를 넘고 변화율도 기준 이상
    HIGH_BREAKOUT,
    // VWAP(거래량 가중 평균가) 위로 기준 거리 이상
    VWAP_BREAKOUT,
    // VWAP 위에 있고 변화율도 기준 이상
    VWAP_RECLAIM,
    // 같은 봉에서 VWAP 아래까지 눌렸다가 앞 6봉 최고가 위로 마감
    PULLBACK_REBREAK
}
