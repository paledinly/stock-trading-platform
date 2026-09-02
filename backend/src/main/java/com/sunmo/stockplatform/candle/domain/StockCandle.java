package com.sunmo.stockplatform.candle.domain;
import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name="stock_candle")
public class StockCandle {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="stock_id") private Stock stock;
 @Column(nullable=false,length=10) private String timeframe;
 @Column(name="start_time",nullable=false) private Instant startTime;
 @Column(nullable=false,precision=20,scale=4) private BigDecimal open;
 @Column(nullable=false,precision=20,scale=4) private BigDecimal high;
 @Column(nullable=false,precision=20,scale=4) private BigDecimal low;
 @Column(nullable=false,precision=20,scale=4) private BigDecimal close;
 @Column(nullable=false) private long volume;
 @Column(name="trading_value",nullable=false,precision=20,scale=4) private BigDecimal tradingValue;
 @Column(name="is_final",nullable=false) private boolean finalCandle;
 @Column(nullable=false) private int revision;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private CandleSource source;
 @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
 @Column(name="updated_at",nullable=false) private Instant updatedAt;
 protected StockCandle(){}
 public StockCandle(Stock stock,Instant start,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,long volume,BigDecimal value,boolean done,int revision){this(stock,start,open,high,low,close,volume,value,done,revision,CandleSource.REALTIME);}
 public StockCandle(Stock stock,Instant start,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,long volume,BigDecimal value,boolean done,int revision,CandleSource source){this.stock=stock;this.timeframe="5M";this.startTime=start;this.source=source;this.createdAt=Instant.now();revise(open,high,low,close,volume,value,done,revision,source);}
 public void revise(BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,long volume,BigDecimal value,boolean done,int revision){revise(open,high,low,close,volume,value,done,revision,CandleSource.REALTIME);}
 public void revise(BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,long volume,BigDecimal value,boolean done,int revision,CandleSource incoming){if(incoming==CandleSource.BACKFILL&&source==CandleSource.REALTIME&&finalCandle)return;this.open=open;this.high=high;this.low=low;this.close=close;this.volume=volume;this.tradingValue=value;this.finalCandle=done;this.revision=Math.max(this.revision,revision);this.source=incoming;this.updatedAt=Instant.now();}
 public Long getId(){return id;} public Instant getStartTime(){return startTime;} public BigDecimal getOpen(){return open;} public BigDecimal getHigh(){return high;} public BigDecimal getLow(){return low;} public BigDecimal getClose(){return close;} public long getVolume(){return volume;} public BigDecimal getTradingValue(){return tradingValue;} public boolean isFinalCandle(){return finalCandle;} public int getRevision(){return revision;} public CandleSource getSource(){return source;}
}
