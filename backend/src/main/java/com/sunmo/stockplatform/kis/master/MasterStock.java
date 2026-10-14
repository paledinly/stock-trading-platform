/*
 * 종목 마스터 파일 한 줄을 해석한 결과(종목코드, 표준코드, 이름, 시장, 종목 유형, 관리종목, 거래정지)를 담는 파일이에요.
 * KisMasterParser가 만들고 StockMasterSyncService가 받아 종목 테이블에 반영해요.
 */
package com.sunmo.stockplatform.kis.master;

import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;

/**
 * 마스터 파일에서 읽은 종목 한 개의 기본 정보예요.
 * 학생 명부의 한 줄(번호, 이름, 반, 특이사항)처럼 종목의 신상 정보만 담아요.
 * stockCode(단축코드), standardCode(표준코드), stockName(종목명), market(KOSPI/KOSDAQ),
 * marketType(주식/ETF/ETN), managed(관리종목 여부), tradingHalted(거래정지 여부)를 돌려줘요.
 */
public record MasterStock(
                String stockCode,
                String standardCode,
                String stockName,
                Market market,
                MarketType marketType,
                boolean managed,
                boolean tradingHalted) {
}
