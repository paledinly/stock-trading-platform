/*
 * KIS 호출 실패를 로그와 진단 숫자에 남기기 좋게 "HTTP 상태 · KIS 오류 코드(msg_cd) · 짧은 설명(msg1)"으로 정리해요.
 * KIS는 초당 호출 한도 초과(EGW00201)를 HTTP 429가 아니라 HTTP 500 본문에 담아 돌려주기도 해서,
 * 예외 이름(InternalServerError)만으로는 한도 초과·인증 문제·서버 장애를 구분할 수 없었어요.
 * 응답 본문의 오류 코드와 설명만 꺼내며, 요청 헤더(토큰·앱키)나 URL은 담지 않아요. 동작(재시도)은 바꾸지 않아요.
 */
package com.sunmo.stockplatform.kis.config;

import org.springframework.web.client.RestClientResponseException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KIS 실패 한 건의 요약이에요. httpStatus는 HTTP 응답이 있었을 때만, code는 KIS가 msg_cd를 줬을 때만 값이 있어요.
 * 받는 것(생성): 상태, 코드, 설명. 쓰는 곳: KisRequestExecutor(공통 실패 기록), InvestorFlowCollector(수급 수집 실패 로그).
 */
public record KisErrorDetail(Integer httpStatus, String code, String message) {
    private static final Pattern MSG_CD = Pattern.compile("\"msg_cd\"\\s*:\\s*\"([^\"]{1,20})\"");
    private static final Pattern MSG1 = Pattern.compile("\"msg1\"\\s*:\\s*\"([^\"]{0,200})\"");
    // KisResponseErrors가 만드는 "문맥 [코드]: 설명" 형식의 코드 부분이에요.
    private static final Pattern BRACKET_CODE = Pattern.compile("\\[([A-Z0-9_]{3,20})]");
    private static final int MESSAGE_LIMIT = 80;

    /**
     * 하는 일: 예외(원인 포함)에서 HTTP 상태와 KIS 오류 코드·설명을 꺼내요.
     * 받는 것: 실패 예외. 돌려주는 것: 요약(아무것도 못 찾으면 예외 클래스 이름을 설명으로 써요).
     */
    public static KisErrorDetail of(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException response) {
                String body = response.getResponseBodyAsString();
                return new KisErrorDetail(response.getStatusCode().value(), find(MSG_CD, body),
                        shorten(find(MSG1, body) == null ? response.getStatusText() : find(MSG1, body)));
            }
            if (current instanceof KisRateLimitException && current.getMessage() != null
                    && current.getMessage().contains("HTTP 429"))
                return new KisErrorDetail(429, null, "KIS HTTP 429");
            String text = current.getMessage();
            String code = text == null ? null : find(BRACKET_CODE, text);
            if (code != null) {
                int colon = text.indexOf("]:");
                return new KisErrorDetail(null, code, shorten(colon < 0 ? text : text.substring(colon + 2)));
            }
        }
        return new KisErrorDetail(null, null, error == null ? null : error.getClass().getSimpleName());
    }

    /** 진단 숫자에 쓰는 묶음 이름이에요. 코드가 있으면 코드, 없으면 "HTTP 500", 둘 다 없으면 "OTHER"예요. */
    public String key() {
        if (code != null) return code;
        return httpStatus != null ? "HTTP " + httpStatus : "OTHER";
    }

    /** 로그용 한 줄이에요. 예: "status=500 msg_cd=EGW00201 msg=초당 거래건수를 초과하였습니다." */
    public String describe() {
        return "status=" + (httpStatus == null ? "-" : httpStatus) + " msg_cd=" + (code == null ? "-" : code)
                + " msg=" + (message == null ? "-" : message);
    }

    private static String find(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 줄바꿈을 없애고 80자까지만 남겨요(로그 한 줄이 길어지지 않게). */
    private static String shorten(String value) {
        if (value == null) return null;
        String single = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return single.length() <= MESSAGE_LIMIT ? single : single.substring(0, MESSAGE_LIMIT) + "…";
    }
}
