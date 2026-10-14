/*
 * API 오류 응답에 실리는 오류 종류 목록이에요.
 * 클라이언트(Web, Mobile)는 이 이름(code)을 보고 어떤 실패인지 구분할 수 있어요.
 * ApplicationException과 GlobalExceptionHandler가 사용해요.
 */
package com.sunmo.stockplatform.common.error;

/**
 * 실패 원인을 짧은 이름으로 정해 둔 목록이에요.
 * 병원 접수표의 "진료과" 칸처럼, 정해진 몇 가지 중 하나를 골라 붙여서 어디로 보낼지(어떻게 처리할지) 알게 해 줘요.
 */
public enum ErrorCode {
    // 요청 값이 형식에 맞지 않아요.
    INVALID_REQUEST,
    // 종목 마스터에 없는 종목이에요.
    STOCK_NOT_FOUND,
    // KIS(한국투자증권) 접속 정보가 설정되지 않았어요.
    KIS_NOT_CONFIGURED,
    // KIS 인증(토큰 발급 등)에 실패했어요.
    KIS_AUTHENTICATION_FAILED,
    // KIS API(외부 서버에 데이터를 요청하는 창구) 호출이 실패했어요.
    KIS_API_ERROR,
    // 시세 데이터가 너무 오래돼서 믿기 어려운 상태로 보여요.
    MARKET_DATA_STALE,
    // 종목 마스터 파일 동기화에 실패했어요.
    STOCK_MASTER_SYNC_FAILED
}
