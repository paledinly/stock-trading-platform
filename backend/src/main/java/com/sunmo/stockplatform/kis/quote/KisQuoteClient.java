/*
 * KIS 주식현재가 시세 API로 한 종목의 현재가, 등락, 시고저가, 누적 거래량과 거래대금을 조회하는 파일이에요.
 * 응답을 KisQuoteMapper로 공통 StockQuote 형태로 바꾸고, 실패는 프로젝트 공통 오류로 바꿔 던져요.
 * quote 패키지의 QuoteService가 QuoteProvider 인터페이스를 통해 이 클라이언트를 써요.
 */
package com.sunmo.stockplatform.kis.quote;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisResponseErrors;
import com.sunmo.stockplatform.quote.application.QuoteProvider;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Stock;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * KIS REST API(인터넷 주소로 요청을 보내 데이터를 받는 방식)로 현재가를 가져오는 QuoteProvider 구현체예요.
 * 증권사 시세 전광판을 보고 지금 가격을 받아 적어 오는 심부름꾼과 같아요.
 */
@Component
public class KisQuoteClient implements QuoteProvider {
    static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-price";
    static final String TR_ID = "FHKST01010100";

    private final RestClient restClient;
    private final KisProperties properties;
    private final KisTokenManager tokenManager;
    private final KisQuoteMapper mapper;
    private final KisRequestExecutor requests;

    /**
     * 현재가 조회에 필요한 부품을 받아요.
     * 받는 것: KIS용 RestClient, KIS 설정, 토큰(API 호출용 임시 출입증) 관리자, 응답 변환기, 호출 속도 조절기.
     * 돌려주는 것: 없음(생성자).
     */
    public KisQuoteClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokenManager,
            KisQuoteMapper mapper, KisRequestExecutor requests) {
        this.restClient = kisRestClient;
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.mapper = mapper;
        this.requests = requests;
    }

    /**
     * 한 종목의 현재가를 KIS에서 조회해요.
     * rate limit(일정 시간당 호출 횟수 제한)과 회로 차단기(실패가 잦으면 잠시 호출을 막는 장치)가 걸려 있고,
     * 공용 속도 조절기에는 현재가 호출(quote=true)로 표시해 더 긴 간격을 지켜요.
     * 받는 것: 종목. 돌려주는 것: StockQuote(현재가 스냅샷).
     */
    @Override
    @RateLimiter(name = "kisQuote")
    @CircuitBreaker(name = "kisQuote")
    public StockQuote getQuote(Stock stock) {
        try {
            properties.requireCredentials();
            return requests.execute(true, () -> {
            var response = restClient.get()
                    .uri(builder -> builder.path(ENDPOINT)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stock.getStockCode())
                            .build())
                    .header("authorization", "Bearer " + tokenManager.getAccessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .body(KisQuoteResponse.class);
            // 실패 응답은 종류에 맞는 예외로 바꿔요(한도 초과면 속도 조절기가 재시도해요).
            if (response == null || !"0".equals(response.resultCode()) || response.output() == null) {
                String code = response == null ? "EMPTY_RESPONSE" : response.messageCode();
                String message = response == null ? "KIS returned an empty response" : response.message();
                KisResponseErrors.failure("KIS quote failed", code, message);
            }
            return mapper.map(stock, response.output());
            });
        } catch (IllegalStateException exception) {
            throw new ApplicationException(ErrorCode.KIS_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE,
                    "KIS integration is not configured", exception);
        } catch (RestClientException exception) {
            throw new ApplicationException(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY,
                    "KIS quote request failed", exception);
        }
    }
}
