/*
 * 미세구조(호가판과 큰 체결처럼 아주 짧은 시간 단위의 시장 모습) 연구 데이터의 값 모양(record)을 모아 둔 파일이에요.
 * 호가 한 단계(Level), 10단계 호가판(Book), 미세구조 1분 집계(Minute)를 정의하고 잘못된 값은 만들 때 거부해요.
 * OrderbookCollector, MicrostructureAggregator, MicrostructureStore, MicrostructureFeatures가 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * 미세구조 데이터 상자들의 모음 클래스예요. 직접 객체로 만들지는 않아요.
 * 시장 진열대 조사 양식 모음집처럼 "가격 한 줄, 진열대 전체, 1분 요약" 양식을 한곳에 모았어요.
 */
public final class MicrostructureModel {
    /** 양식 모음용 클래스라 객체를 만들지 못하게 막아 둔 생성자예요. */
    private MicrostructureModel() {}
    /** 호가 한 단계(가격, 잔량)예요. 음수이거나, 가격 0인데 잔량이 있으면 거부해요. */
    public record Level(BigDecimal price, BigDecimal quantity) {
        public Level {
            if (price == null || quantity == null || price.signum() < 0 || quantity.signum() < 0
                    || price.signum() == 0 && quantity.signum() > 0) throw new IllegalArgumentException("Invalid depth level");
        }
    }
    /** 한 종목의 호가판이에요. 원천 접수시각, 수신시각, 매수(bids)·매도(asks) 각 10단계를 담아요. 10단계가 아니면 거부해요. */
    public record Book(String symbol, Instant sourceAt, Instant receivedAt, List<Level> bids, List<Level> asks) {
        public Book {
            bids = List.copyOf(bids); asks = List.copyOf(asks);
            if (bids.size() != 10 || asks.size() != 10) throw new IllegalArgumentException("Ten levels required");
        }
    }
    /**
     * 미세구조 1분 집계 한 줄이에요. kind는 ORDERBOOK 또는 LARGE_EXECUTION, occurredAt은 마지막 원천시각,
     * receivedAt은 마지막 수신시각, finalizedAt은 확정 시각이에요. metrics는 읽기 전용 지표 묶음이에요.
     */
    public record Minute(String kind, String symbol, Instant start, Instant occurredAt, Instant receivedAt,
            Instant finalizedAt, boolean complete, Map<String, Object> metrics) {
        public Minute { metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics)); }
    }
}
