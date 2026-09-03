package com.sunmo.stockplatform.kis.quote;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.quote.application.QuoteProvider;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Stock;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class KisQuoteClient implements QuoteProvider {
    static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-price";
    static final String TR_ID = "FHKST01010100";

    private final RestClient restClient;
    private final KisProperties properties;
    private final KisTokenManager tokenManager;
    private final KisQuoteMapper mapper;

    public KisQuoteClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokenManager,
            KisQuoteMapper mapper) {
        this.restClient = kisRestClient;
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.mapper = mapper;
    }

    @Override
    @RateLimiter(name = "kisQuote")
    @CircuitBreaker(name = "kisQuote")
    public StockQuote getQuote(Stock stock) {
        try {
            properties.requireCredentials();
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
            if (response == null || !"0".equals(response.resultCode()) || response.output() == null) {
                String code = response == null ? "EMPTY_RESPONSE" : response.messageCode();
                String message = response == null ? "KIS returned an empty response" : response.message();
                throw new ApplicationException(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY,
                        "KIS quote failed [%s]: %s".formatted(code, message));
            }
            return mapper.map(stock, response.output());
        } catch (IllegalStateException exception) {
            throw new ApplicationException(ErrorCode.KIS_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE,
                    "KIS integration is not configured", exception);
        } catch (RestClientException exception) {
            throw new ApplicationException(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY,
                    "KIS quote request failed", exception);
        }
    }
}
