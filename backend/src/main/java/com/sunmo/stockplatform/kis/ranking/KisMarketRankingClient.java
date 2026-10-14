/*
 * KIS(한국투자증권) 순위 API(프로그램끼리 정보를 주고받는 약속된 창구)를 불러 거래대금·거래량·상승률·체결강도·신고가 근접 순위를 가져와요.
 * 응답 행을 KisRankingEntry 목록으로 바꾸고, 받은 순위를 ObservedRanking 이벤트로 알려 다른 수집기도 기록할 수 있게 해요.
 * 시장 전체 스캔(BroadCandidateCollector)과 상한가 감시(LimitUpWatchService)가 MarketRankingProvider 이름으로 이 클래스를 사용해요.
 */
package com.sunmo.stockplatform.kis.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisResponseErrors;
import com.sunmo.stockplatform.stock.domain.Market;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * KIS에 시장 순위표를 요청하고, 받은 표를 우리 프로젝트 형식으로 옮겨 적는 일을 맡은 클래스예요.
 * 증권사 객장의 "거래대금 상위", "상승률 상위" 전광판을 보고 위에서부터 필요한 줄 수만큼 받아 적어 오는 직원이라고 생각하면 돼요.
 */
@Component
public class KisMarketRankingClient implements MarketRankingProvider {
    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;
    // 순위를 받을 때마다 이벤트(“이런 일이 있었어요” 하고 다른 부품에 보내는 알림)를 보내는 확성기예요. 없으면 알림을 생략해요.
    private org.springframework.context.ApplicationEventPublisher publisher;

    /**
     * 이벤트 발행기(ApplicationEventPublisher)를 넣어 받아요.
     * 받는 것: 스프링이 제공하는 이벤트 발행기. 돌려주는 것: 없음.
     * 생성자가 아니라 setter로 받아서, 발행기가 없는 테스트 환경에서도 이 클래스를 만들 수 있게 한 것으로 보여요.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setPublisher(org.springframework.context.ApplicationEventPublisher publisher) { this.publisher = publisher; }

    /**
     * 의존성 주입(필요한 부품을 스프링이 대신 만들어 넣어 주는 방식)으로 KIS 호출에 필요한 부품을 받아요.
     * 받는 것: KIS용 REST(웹 주소와 HTTP 방식으로 데이터를 주고받는 규칙) 클라이언트, KIS 설정값, 토큰(로그인 대신 쓰는 출입증) 관리자, 호출 재시도 실행기.
     * 돌려주는 것: 순위 조회 준비가 끝난 클라이언트 객체.
     */
    public KisMarketRankingClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    /**
     * 지정한 순위 종류와 시장의 상위 종목을 KIS에서 받아 와요.
     * rate limit(일정 시간 동안 보낼 수 있는 요청 수 제한)과 서킷 브레이커(실패가 계속되면 잠시 호출을 끊어 버티는 장치)가 함께 걸려 있어요.
     * 받는 것: 순위 종류(type), 시장(market, null이면 전체 시장), 최대 개수(limit, 최소 1개).
     * 돌려주는 것: 순위 순서대로 정리된 KisRankingEntry 목록(수정할 수 없는 목록).
     * 인증정보가 없으면 KIS_NOT_CONFIGURED(503), 통신 실패면 KIS_API_ERROR(502) 오류를 던져요.
     */
    @Override
    @RateLimiter(name = "kisRanking")
    @CircuitBreaker(name = "kisRanking")
    public List<KisRankingEntry> fetch(RankingType type, Market market, int limit) {
        try {
            // 준비: 인증정보를 확인하고, 순위 종류에 맞는 KIS 주소와 거래 ID(tr_id)를 골라요.
            properties.requireCredentials();
            Endpoint endpoint = Endpoint.forType(type);
            // 요청: 재시도 실행기를 통해 KIS를 부르고, 응답이 성공 형식인지 바로 검사해요.
            JsonNode response = requests.execute(false, () -> {
                JsonNode body = client.get()
                    .uri(builder -> endpoint.apply(builder.path(endpoint.path()), market, limit).build())
                    .header("authorization", "Bearer " + tokens.getAccessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", endpoint.trId())
                    .header("custtype", "P")
                    .retrieve()
                    .body(JsonNode.class);
                validate(body, type);
                return body;
            });
            // 처리: 응답의 각 행을 순위 항목으로 바꿔요. 종목코드가 없는 행은 건너뛰고, 순위는 1부터 다시 매겨요.
            List<KisRankingEntry> entries = new ArrayList<>();
            int rank = 1;
            for (JsonNode row : response.path("output")) {
                String code = text(row, "mksc_shrn_iscd", "stck_shrn_iscd");
                if (code == null || code.isBlank())
                    continue;
                entries.add(new KisRankingEntry(code, text(row, "hts_kor_isnm", "stck_kor_isnm"), rank++,
                        decimal(row, "stck_prpr"), decimal(row, "prdy_ctrt"), number(row, "acml_vol"),
                        decimal(row, "acml_tr_pbmn"), decimal(row, "tday_rltv", "cntg_strth"),
                        decimal(row, "stck_oprc"), decimal(row, "stck_hgpr"), decimal(row, "stck_lwpr")));
                if (entries.size() >= Math.max(1, limit))
                    break;
            }
            // 결과: 받은 순위를 이벤트로 알리고(수신시각 포함) 목록을 돌려줘요.
            // ⚠️ 확인 필요: publishEvent는 기본적으로 같은 스레드에서 리스너를 바로 실행해요. 리스너(예: TrajectoryContextCollector)가
            // 예외를 던지면 이미 받은 순위 결과도 버려지고 서킷 브레이커 실패로 집계될 수 있어요.
            var result = List.copyOf(entries);
            if (publisher != null) publisher.publishEvent(new ObservedRanking(type, market, java.time.Instant.now(), result));
            return result;
        } catch (IllegalStateException exception) {
            throw new ApplicationException(ErrorCode.KIS_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE,
                    "KIS integration is not configured", exception);
        } catch (RestClientException exception) {
            throw new ApplicationException(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY,
                    "KIS market ranking request failed: " + type, exception);
        }
    }

    /**
     * KIS 응답이 정상인지 검사해요.
     * 받는 것: 응답 본문(response)과 순위 종류(type). 돌려주는 것: 없음.
     * rt_cd가 "0"이 아니거나 output 배열이 없으면 KisResponseErrors로 알맞은 예외(호출 한도 초과, 입력 거부, 일반 실패)를 던져요.
     */
    private void validate(JsonNode response, RankingType type) {
        if (response == null || !"0".equals(response.path("rt_cd").asText()) || !response.path("output").isArray()) {
            String code = response == null ? "EMPTY_RESPONSE" : response.path("msg_cd").asText("UNKNOWN");
            String message = response == null ? "KIS returned an empty response" : response.path("msg1").asText();
            KisResponseErrors.failure("KIS market ranking failed " + type, code, message);
        }
    }

    /**
     * 여러 후보 필드 이름 중 처음으로 값이 있는 필드의 글자를 꺼내요.
     * 받는 것: JSON 행과 필드 이름들. 돌려주는 것: 앞뒤 공백을 지운 값, 모두 비어 있으면 null.
     * 순위 API마다 같은 정보의 필드 이름이 달라서(예: mksc_shrn_iscd / stck_shrn_iscd) 이렇게 처리해요.
     */
    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText();
            if (!value.isBlank())
                return value.trim();
        }
        return null;
    }

    /**
     * 필드 값을 숫자(BigDecimal)로 바꿔요.
     * 받는 것: JSON 행과 필드 이름들. 돌려주는 것: 쉼표를 뺀 숫자, 값이 없거나 숫자가 아니면 null.
     * 결측을 0으로 바꾸지 않고 null로 남겨서 "모름"과 "0"을 구분해요.
     */
    private BigDecimal decimal(JsonNode node, String... fields) {
        String value = text(node, fields);
        if (value == null)
            return null;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 필드 값을 정수(Long)로 바꿔요. 받는 것: JSON 행과 필드 이름. 돌려주는 것: 소수점 아래를 버린 정수, 없으면 null.
     */
    private Long number(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        return value == null ? null : value.longValue();
    }

    /**
     * 순위 종류별 KIS 주소, 거래 ID, 요청 파라미터를 묶어 둔 내부 기록이에요.
     * 같은 "순위표 보기"라도 전광판마다 버튼이 다르듯, 순위 종류마다 눌러야 할 주소와 설정을 정리해 둔 안내표예요.
     */
    private record Endpoint(String path, String trId, RankingType type) {
        /**
         * 순위 종류에 맞는 주소와 거래 ID를 골라요.
         * 받는 것: 순위 종류. 돌려주는 것: 해당 Endpoint. 거래대금(TURNOVER)과 거래량(VOLUME)은 같은 거래량순위 API를 써요.
         */
        private static Endpoint forType(RankingType type) {
            return switch (type) {
                case TURNOVER, VOLUME -> new Endpoint("/uapi/domestic-stock/v1/quotations/volume-rank",
                        "FHPST01710000", type);
                case PRICE_RISE -> new Endpoint("/uapi/domestic-stock/v1/ranking/fluctuation",
                        "FHPST01700000", type);
                case TRADE_STRENGTH -> new Endpoint("/uapi/domestic-stock/v1/ranking/volume-power",
                        "FHPST01680000", type);
                case HIGH_PROXIMITY -> new Endpoint("/uapi/domestic-stock/v1/ranking/near-new-highlow",
                        "FHPST01870000", type);
            };
        }

        /**
         * KIS 순위 요청에 필요한 조회 조건(query parameter)을 주소에 붙여요.
         * 받는 것: 주소 작성기, 시장, 최대 개수. 돌려주는 것: 조건이 붙은 주소 작성기.
         */
        private UriBuilder apply(UriBuilder builder, Market market, int limit) {
            // 시장 코드: 코스피 0001, 코스닥 1001, 그 밖(전체)은 0000이에요.
            String marketCode = market == Market.KOSPI ? "0001" : market == Market.KOSDAQ ? "1001" : "0000";
            builder.queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", marketCode)
                    .queryParam("FID_TRGT_CLS_CODE", "0")
                    .queryParam("FID_TRGT_EXLS_CLS_CODE", "0")
                    .queryParam("FID_DIV_CLS_CODE", "0")
                    // 거래대금 순위는 소속 구분을 "3"(거래금액순)으로 바꿔 거래량순위 API에서 거래대금 기준 정렬을 받는 것으로 보여요.
                    .queryParam("FID_BLNG_CLS_CODE", type == RankingType.TURNOVER ? "3" : "0")
                    .queryParam("FID_INPUT_PRICE_1", "")
                    .queryParam("FID_INPUT_PRICE_2", "")
                    .queryParam("FID_VOL_CNT", "")
                    .queryParam("FID_INPUT_DATE_1", "")
                    .queryParam("FID_INPUT_OPTION_1", "0")
                    .queryParam("FID_INPUT_OPTION_2", "0")
                    .queryParam("FID_RANK_SORT_CLS_CODE", sortCode())
                    .queryParam("FID_COND_SCR_DIV_CODE", screenCode());
            // 신고가 근접 순위 API에서만 쓰는 추가 조건이에요.
            if (type == RankingType.HIGH_PROXIMITY) {
                builder.queryParam("FID_APLY_RANG_VOL", "0")
                        .queryParam("FID_INPUT_CNT_1", "0")
                        .queryParam("FID_INPUT_CNT_2", "100")
                        .queryParam("FID_PRC_CLS_CODE", "0")
                        .queryParam("FID_APLY_RANG_PRC_1", "")
                        .queryParam("FID_APLY_RANG_PRC_2", "");
            }
            // 상승률 순위 API는 받을 개수를 직접 지정해요(1~100개 사이로 제한).
            if (type == RankingType.PRICE_RISE) {
                builder.queryParam("FID_INPUT_CNT_1", String.valueOf(Math.min(100, Math.max(1, limit))))
                        .queryParam("FID_PRC_CLS_CODE", "0")
                        .queryParam("FID_RSFL_RATE1", "")
                        .queryParam("FID_RSFL_RATE2", "");
            }
            return builder;
        }

        /** 정렬 구분 코드를 돌려줘요. 거래대금 순위만 "3", 나머지는 "0"이에요. */
        private String sortCode() {
            return type == RankingType.TURNOVER ? "3" : "0";
        }

        /** KIS 화면 구분 코드(순위 종류마다 정해진 번호)를 돌려줘요. */
        private String screenCode() {
            return switch (type) {
                case TURNOVER, VOLUME -> "20171";
                case PRICE_RISE -> "20170";
                case TRADE_STRENGTH -> "20168";
                case HIGH_PROXIMITY -> "20187";
            };
        }
    }
}
