/*
 * 종목의 상품 유형(일반 주식, ETF, ETN)을 나타내는 파일이에요.
 * 종목 엔티티와 마스터 파서가 사용하고, 스캔 대상에서 ETF/ETN을 거를 때 쓰여요.
 */
package com.sunmo.stockplatform.stock.domain;

/**
 * 종목의 상품 종류 구분이에요.
 * 마트 상품에 "일반 식품 / 묶음 상품 / 상품권" 같은 분류 스티커를 붙이는 것처럼, 같은 시장 안에서도 상품 성격을 나눠요.
 */
public enum MarketType {
    STOCK,
    ETF,
    ETN
}
