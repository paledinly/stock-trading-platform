/*
 * KIS "종목별 투자자매매동향(일별)"(FHPTJ04160001)로 한 종목의 외국인·기관·개인 일별 순매수를 받는 클라이언트예요(연구용).
 * 기준일을 주면 그날까지 30거래일을 돌려줘요(2026-11-03 시험 조회: 2015년 날짜까지 응답). 점수나 선정에는 쓰지 않아요.
 */
package com.sunmo.stockplatform.kis.investor;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisResponseErrors;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 종목별 투자자 일별 순매수 조회 클라이언트예요. */
@Component
public class KisInvestorDailyClient {
    private static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/investor-trade-by-stock-daily";
    private static final String TR_ID = "FHPTJ04160001";
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 하루치 순매수예요. qty는 주, amount는 백만 원(KIS 단위 그대로), 음수는 순매도예요.
     * 기관 세부: securities 금융투자, trust 투신, privateFund 사모, bank 은행, insurance 보험, merchantBank 종금,
     * pension 연기금 등, otherCorp 기타법인.
     */
    public record DailyFlow(LocalDate date, Long foreignQty, Long institutionQty, Long individualQty, Long foreignAmount,
            Long institutionAmount, Long individualAmount, Long securitiesAmount, Long trustAmount,
            Long privateFundAmount, Long bankAmount, Long insuranceAmount, Long merchantBankAmount, Long pensionAmount,
            Long otherCorpAmount) { }

    /** 하루치 가격(원주가, 원)과 거래량(주)·거래대금(원)에 순매수를 붙인 행이에요. 상장폐지 종목 연구용이에요. */
    public record DailyRow(DailyFlow flow, Long open, Long high, Long low, Long close, Long volume, Long tradingValue) { }

    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    /** 하는 일: KIS 공용 RestClient, 설정, 토큰 관리자, 공용 속도 조절기를 받아 둬요. */
    public KisInvestorDailyClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    /**
     * 하는 일: 기준일까지 30거래일의 투자자별 순매수를 받아요. 호출 1회예요.
     * 받는 것: 종목코드, 기준일. 돌려주는 것: 날짜 오름차순 목록(그 종목이 상장 전이면 비어 있어요).
     */
    public List<DailyFlow> fetch(String stockCode, LocalDate date) {
        return fetchRows(stockCode, date).stream().map(DailyRow::flow).toList();
    }

    /**
     * 하는 일: fetch와 같은 조회(호출 1회)에서 가격·거래량·거래대금까지 읽어요.
     * 돌려주는 것: 날짜 오름차순 목록. 가격은 KIS가 준 값 그대로예요(수정주가로 바꾸지 않아요).
     */
    public List<DailyRow> fetchRows(String stockCode, LocalDate date) {
        properties.requireCredentials();
        JsonNode body = requests.execute(false, () -> {
            JsonNode response = client.get().uri(builder -> builder.path(ENDPOINT)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .queryParam("FID_INPUT_DATE_1", date.format(DATE))
                            .queryParam("FID_ORG_ADJ_PRC", "")
                            .queryParam("FID_ETC_CLS_CODE", "")
                            .build())
                    .header("authorization", "Bearer " + tokens.getAccessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve().body(JsonNode.class);
            if (response == null || !"0".equals(response.path("rt_cd").asText()))
                KisResponseErrors.failure("KIS investor daily failed",
                        response == null ? "EMPTY" : response.path("msg_cd").asText(), "Investor daily flow unavailable");
            return response;
        });
        List<DailyRow> rows = new ArrayList<>();
        for (JsonNode row : body.path("output2")) {
            String raw = row.path("stck_bsop_date").asText("").trim();
            if (raw.length() != 8)
                continue;
            DailyFlow flow = new DailyFlow(LocalDate.parse(raw, DATE), number(row, "frgn_ntby_qty"), number(row, "orgn_ntby_qty"),
                    number(row, "prsn_ntby_qty"), number(row, "frgn_ntby_tr_pbmn"), number(row, "orgn_ntby_tr_pbmn"),
                    number(row, "prsn_ntby_tr_pbmn"), number(row, "scrt_ntby_tr_pbmn"), number(row, "ivtr_ntby_tr_pbmn"),
                    number(row, "pe_fund_ntby_tr_pbmn"), number(row, "bank_ntby_tr_pbmn"), number(row, "insu_ntby_tr_pbmn"),
                    number(row, "mrbn_ntby_tr_pbmn"), number(row, "fund_ntby_tr_pbmn"), number(row, "etc_corp_ntby_tr_pbmn"));
            rows.add(new DailyRow(flow, number(row, "stck_oprc"), number(row, "stck_hgpr"), number(row, "stck_lwpr"),
                    number(row, "stck_clpr"), number(row, "acml_vol"), number(row, "acml_tr_pbmn")));
        }
        rows.sort(java.util.Comparator.comparing(r -> r.flow().date()));
        return rows;
    }

    /** 하는 일: 정수 필드를 읽어요. 비었거나 숫자가 아니면 null이에요(0으로 채우지 않아요). */
    private static Long number(JsonNode row, String field) {
        String value = row.path(field).asText("").trim().replace(",", "");
        if (value.isEmpty())
            return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
