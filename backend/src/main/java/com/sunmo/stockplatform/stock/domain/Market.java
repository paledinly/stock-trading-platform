/*
 * 종목이 상장된 시장(KOSPI, KOSDAQ)을 나타내는 파일이에요.
 * 종목 마스터, 시장 전체 스캔, 화면 표시 등 여러 곳에서 사용해요.
 */
package com.sunmo.stockplatform.stock.domain;

/**
 * 종목이 속한 거래 시장 구분이에요.
 * 학생이 1반인지 2반인지 적어 두는 반 표시처럼, 종목이 어느 시장 소속인지 알려 줘요.
 */
public enum Market {
    KOSPI,
    KOSDAQ
}
