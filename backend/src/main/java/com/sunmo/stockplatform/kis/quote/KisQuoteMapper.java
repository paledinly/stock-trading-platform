/*
 * KIS 현재가 응답(문자열 필드)을 프로젝트 공통 시세 객체 StockQuote로 바꾸는 파일이에요.
 * 전일 대비 부호 코드를 보고 하락이면 등락 값을 음수로 바꾸고, 받은 시각을 함께 기록해요.
 * KisQuoteClient가 응답을 받은 직후 이 변환기를 불러요.
 */
package com.sunmo.stockplatform.kis.quote;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * KIS 응답을 StockQuote로 옮겨 적는 변환기예요.
 * 외국 영수증의 숫자를 우리 회사 장부 양식에 맞춰 옮겨 적는 경리 직원과 같아요.
 */
@Component
class KisQuoteMapper {
    private final Clock clock;

    /** 운영용 생성자예요. 시스템 시계(UTC)를 써요. */
    KisQuoteMapper() {
        this(Clock.systemUTC());
    }

    /** 시계를 직접 넣는 생성자예요. 테스트에서 시각을 고정할 때 쓰는 것으로 보여요. */
    KisQuoteMapper(Clock clock) {
        this.clock = clock;
    }

    /**
     * KIS 시세 값을 StockQuote로 바꿔요.
     * 받는 것: 종목(코드·이름·시장), KIS 시세 Output. 돌려주는 것: StockQuote.
     * 마지막 시각은 KIS 원천 시각이 아니라 이 서버가 변환한 시각(수신 시각에 가까운 값)이에요.
     */
    StockQuote map(Stock stock, KisQuoteResponse.Output output) {
        // 현재가가 비었거나 0 이하면 0원 시세를 만들지 않고 거절로 다뤄요(호출한 쪽이 실패로 처리하고, 회로 차단기는 세지 않아요).
        String current = output.currentPrice();
        if (current == null || current.isBlank() || new BigDecimal(current.trim()).signum() <= 0)
            throw new com.sunmo.stockplatform.kis.config.KisRequestRejectedException(
                    "KIS quote has no current price for " + stock.getStockCode());
        return new StockQuote(stock.getStockCode(), stock.getStockName(), stock.getMarket().name(),
                decimal(output.currentPrice()), signedDecimal(output.change(), output.previousDaySign()),
                signedDecimal(output.changeRate(), output.previousDaySign()),
                decimal(output.openPrice()), decimal(output.highPrice()), decimal(output.lowPrice()),
                longValue(output.accumulatedVolume()), decimal(output.accumulatedTradingValue()), clock.instant());
    }

    /**
     * 문자열을 숫자로 바꿔요.
     * 받는 것: 숫자 문자열(null 가능). 돌려주는 것: BigDecimal, 비어 있으면 0.
     */
    private BigDecimal decimal(String value) {
        // 현재가는 map()에서 먼저 확인해요. 시가·고가·저가는 장 시작 전에 0일 수 있어 그대로 0으로 둬요.
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
    }

    /**
     * 부호 코드를 반영한 등락 값을 만들어요.
     * KIS 부호 코드 4(하한), 5(하락)이면 음수, 그 외(상한·상승·보합)는 양수 또는 0으로 둬요.
     * 받는 것: 숫자 문자열, 부호 코드. 돌려주는 것: 부호가 붙은 BigDecimal.
     */
    private BigDecimal signedDecimal(String value, String signCode) {
        BigDecimal number = decimal(value).abs();
        return "4".equals(signCode) || "5".equals(signCode) ? number.negate() : number;
    }

    /**
     * 문자열을 정수로 바꿔요.
     * 받는 것: 숫자 문자열(null 가능). 돌려주는 것: long, 비어 있으면 0.
     */
    private long longValue(String value) {
        return value == null || value.isBlank() ? 0L : Long.parseLong(value.trim());
    }
}
