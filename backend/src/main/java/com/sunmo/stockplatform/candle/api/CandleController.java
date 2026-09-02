package com.sunmo.stockplatform.candle.api;
import com.sunmo.stockplatform.candle.application.CandleQueryService;
import com.sunmo.stockplatform.stock.application.StockService;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
@Validated @RestController @RequestMapping("/api/v1/stocks")
public class CandleController {private final CandleQueryService candles;public CandleController(CandleQueryService candles){this.candles=candles;}@GetMapping("/{stockCode}/candles")public List<Response> get(@PathVariable @Pattern(regexp="[A-Z0-9]{6,12}")String stockCode,@RequestParam(defaultValue="5M")String timeFrame,@RequestParam Instant from,@RequestParam Instant to){return candles.get(stockCode,timeFrame,from,to).stream().map(c->new Response(c.getStartTime(),c.getOpen(),c.getHigh(),c.getLow(),c.getClose(),c.getVolume(),c.getTradingValue(),c.isFinalCandle(),c.getRevision(),c.getSource().name())).toList();}public record Response(Instant startTime,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,long volume,BigDecimal tradingValue,boolean finalCandle,int revision,String source){}}
