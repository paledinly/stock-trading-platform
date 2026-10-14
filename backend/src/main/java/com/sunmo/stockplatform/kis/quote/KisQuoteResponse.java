/*
 * KIS 주식현재가 시세 API의 JSON 응답 모양을 그대로 옮겨 둔 파일이에요.
 * KIS의 짧은 필드 이름(stck_prpr 등)을 읽기 쉬운 이름에 연결해요.
 * KisQuoteClient가 응답을 받을 때 쓰고, KisQuoteMapper가 Output을 StockQuote로 바꿔요.
 */
package com.sunmo.stockplatform.kis.quote;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KIS 현재가 응답 전체예요. 결과 코드(rt_cd, "0"이면 성공), 메시지 코드, 메시지, 실제 시세(output)를 담아요.
 * 외국어 영수증을 받아 칸마다 우리말 이름표를 붙여 둔 것과 같아요.
 */
record KisQuoteResponse(
                @JsonProperty("rt_cd") String resultCode,
                @JsonProperty("msg_cd") String messageCode,
                @JsonProperty("msg1") String message,
                Output output) {
        /**
         * 실제 시세 값들이에요. KIS가 숫자도 문자열로 보내서 모두 String으로 받아요.
         * currentPrice(현재가), previousDaySign(전일 대비 부호 코드), change(전일 대비), changeRate(등락률),
         * openPrice/highPrice/lowPrice(시가/고가/저가), accumulatedVolume(누적 거래량), accumulatedTradingValue(누적 거래대금).
         */
        record Output(
                        @JsonProperty("stck_prpr") String currentPrice,
                        @JsonProperty("prdy_vrss_sign") String previousDaySign,
                        @JsonProperty("prdy_vrss") String change,
                        @JsonProperty("prdy_ctrt") String changeRate,
                        @JsonProperty("stck_oprc") String openPrice,
                        @JsonProperty("stck_hgpr") String highPrice,
                        @JsonProperty("stck_lwpr") String lowPrice,
                        @JsonProperty("acml_vol") String accumulatedVolume,
                        @JsonProperty("acml_tr_pbmn") String accumulatedTradingValue) {
        }
}
