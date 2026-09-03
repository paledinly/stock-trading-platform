package com.sunmo.stockplatform.kis.quote;

import com.fasterxml.jackson.annotation.JsonProperty;

record KisQuoteResponse(
                @JsonProperty("rt_cd") String resultCode,
                @JsonProperty("msg_cd") String messageCode,
                @JsonProperty("msg1") String message,
                Output output) {
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
