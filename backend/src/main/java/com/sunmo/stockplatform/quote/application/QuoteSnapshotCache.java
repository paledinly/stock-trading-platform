/*
 * 종목별 최신 현재가 스냅샷을 메모리에 잠깐 보관하는 캐시(자주 쓰는 값을 가까이 저장해 두는 임시 보관함)예요.
 * KIS REST로 받은 실제 기준 시세나, 그 시세에 실시간 틱을 합친 결과만 보관해요.
 * QuoteService(단건 현재가 조회)와 BroadQuoteResolver(시장 전체 스캔)가 함께 써요.
 */
package com.sunmo.stockplatform.quote.application;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared real REST/merged baselines; no subscriptions and no synthesized tick-only baselines.
 * (한국어) 실제 REST 기준 시세와 합친 결과만 공유하는 보관함이에요. 실시간 구독을 만들지 않고, 틱만으로 지어낸 기준 시세도 넣지 않아요.
 * 편의점 진열대의 "오늘 들어온 신문"처럼 종목마다 가장 최신 한 부만 두고, 날짜가 지난 것은 꺼내 주지 않아요.
 */
@Component
public class QuoteSnapshotCache {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Map<String, StockQuote> values = new ConcurrentHashMap<>();
    /**
     * 오늘(서울 기준) 날짜의 스냅샷만 꺼내요.
     * 받는 것: 종목 코드, 기준 시각 now. 돌려주는 것: 시세 시각이 now 이전이면서 now와 같은 날짜인 스냅샷(없으면 빈 Optional).
     */
    public Optional<StockQuote> today(String code, Instant now) {
        return Optional.ofNullable(values.get(code)).filter(quote -> quote.quotedAt() != null
                && !quote.quotedAt().isAfter(now)
                && quote.quotedAt().atZone(SEOUL).toLocalDate().equals(now.atZone(SEOUL).toLocalDate()));
    }
    /**
     * 오늘 스냅샷 중에서도 maxAge 이내로 충분히 최근인 것만 꺼내요.
     * 받는 것: 종목 코드, 기준 시각, 허용 나이. 돌려주는 것: 조건을 만족하는 스냅샷(없으면 빈 Optional).
     */
    public Optional<StockQuote> fresh(String code, Instant now, Duration maxAge) {
        return today(code, now).filter(quote -> !quote.quotedAt().isBefore(now.minus(maxAge)));
    }
    /**
     * 스냅샷을 저장해요. 이미 있는 값보다 시세 시각이 더 늦을 때만 바꿔서, 과거 값이 최신 값을 덮어쓰지 않게 해요.
     * 받는 것: 저장할 스냅샷. 돌려주는 것: 없음.
     */
    public void remember(StockQuote quote) {
        values.compute(quote.stockCode(), (code, previous) -> previous == null
                || quote.quotedAt().isAfter(previous.quotedAt()) ? quote : previous);
    }
}
