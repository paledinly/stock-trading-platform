/*
 * stock_candle 테이블의 한 행(봉 하나)을 나타내는 JPA 엔티티예요(JPA: 자바 객체와 DB 테이블을 자동으로 연결해 주는 기술, 엔티티: 테이블 한 행에 대응하는 객체).
 * 5분봉(5M)과 일봉(1D)을 같은 테이블에 timeframe으로 구분해 저장하고, 출처(REALTIME/BACKFILL)와 확정 여부를 함께 기록해요.
 * MarketDataService(실시간 확정봉), CandleBackfillService·DailyCandlePersistence(보충), 조회·백테스트·추천 서비스가 사용해요.
 */
package com.sunmo.stockplatform.candle.domain;

import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * DB에 저장된 봉 한 개예요. 출석부의 한 칸처럼 "어느 종목, 어느 시간 칸"에 시가·고가·저가·종가·거래량·거래대금을 적어 둬요.
 * 고쳐 쓸 때는 revise 규칙을 지켜서, 보충 데이터나 늦게 온 같은 버전 데이터가 실시간 확정봉을 덮어쓰지 못하게 해요.
 */
@Entity
@Table(name = "stock_candle")
public class StockCandle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id")
    private Stock stock;
    @Column(nullable = false, length = 10)
    private String timeframe;
    @Column(name = "start_time", nullable = false)
    private Instant startTime;
    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal open;
    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal high;
    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal low;
    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal close;
    @Column(nullable = false)
    private long volume;
    @Column(name = "trading_value", nullable = false, precision = 20, scale = 4)
    private BigDecimal tradingValue;
    @Column(name = "is_final", nullable = false)
    private boolean finalCandle;
    @Column(nullable = false)
    private int revision;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CandleSource source;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA가 DB 행을 객체로 만들 때 쓰는 빈 생성자예요. 직접 호출하지 않아요. */
    protected StockCandle() {
    }

    /**
     * 하는 일: 실시간(REALTIME) 출처의 5분봉을 만들어요.
     * 받는 것: 종목, 봉 시작시각, 시·고·저·종가, 거래량, 거래대금, 확정 여부(done), 수정 번호(revision).
     */
    public StockCandle(Stock stock, Instant start, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            long volume, BigDecimal value, boolean done, int revision) {
        this(stock, start, open, high, low, close, volume, value, done, revision, CandleSource.REALTIME);
    }

    /**
     * 하는 일: 출처를 지정해 5분봉을 만들어요.
     * 받는 것: 위와 같고 출처(source)가 추가돼요.
     */
    public StockCandle(Stock stock, Instant start, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            long volume, BigDecimal value, boolean done, int revision, CandleSource source) {
        this(stock, "5M", start, open, high, low, close, volume, value, done, revision, source);
    }

    /**
     * 하는 일: 봉 단위(timeframe, 예: "5M", "1D")와 출처를 모두 지정해 봉을 만들어요. 값 채우기는 revise에 맡겨요.
     * 받는 것: 종목, 봉 단위, 시작시각, 가격들, 거래량, 거래대금, 확정 여부, 수정 번호, 출처.
     */
    public StockCandle(Stock stock, String timeframe, Instant start, BigDecimal open, BigDecimal high, BigDecimal low,
            BigDecimal close, long volume, BigDecimal value, boolean done, int revision, CandleSource source) {
        this.stock = stock;
        this.timeframe = timeframe;
        this.startTime = start;
        this.source = source;
        this.createdAt = Instant.now();
        revise(open, high, low, close, volume, value, done, revision, source);
    }

    /**
     * 하는 일: 실시간(REALTIME) 값으로 봉을 고쳐 써요.
     * 받는 것: 가격들, 거래량, 거래대금, 확정 여부, 수정 번호. 돌려주는 것: 없음.
     */
    public void revise(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume,
            BigDecimal value, boolean done, int revision) {
        revise(open, high, low, close, volume, value, done, revision, CandleSource.REALTIME);
    }

    /**
     * 하는 일: 들어온 출처(incoming)를 보고 봉을 고쳐 쓸지 결정한 뒤 값을 바꿔요.
     * 받는 것: 가격들, 거래량, 거래대금, 확정 여부, 수정 번호, 들어온 출처. 돌려주는 것: 없음.
     * 무시하는 경우: ① 보충 값이 실시간 확정봉을 덮으려 할 때 ② 실시간 확정봉에 수정 번호가 같거나 낮은 실시간 값이 올 때.
     */
    public void revise(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume,
            BigDecimal value, boolean done, int revision, CandleSource incoming) {
        if (incoming == CandleSource.BACKFILL && source == CandleSource.REALTIME && finalCandle)
            return;
        if (incoming == CandleSource.REALTIME && source == CandleSource.REALTIME && finalCandle
                && revision <= this.revision)
            return;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.tradingValue = value;
        this.finalCandle = done;
        this.revision = Math.max(this.revision, revision);
        this.source = incoming;
        this.updatedAt = Instant.now();
    }

    // 아래는 값을 읽기만 하는 getter예요. 각 필드 값을 그대로 돌려줘요.
    public Long getId() {
        return id;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public BigDecimal getClose() {
        return close;
    }

    public long getVolume() {
        return volume;
    }

    public BigDecimal getTradingValue() {
        return tradingValue;
    }

    public boolean isFinalCandle() {
        return finalCandle;
    }

    public int getRevision() {
        return revision;
    }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public CandleSource getSource() {
        return source;
    }
}
