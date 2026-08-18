package com.sunmo.stockplatform.trade.api;
import com.sunmo.stockplatform.trade.domain.TradeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
public final class TradeRequests{
 private TradeRequests(){}
 public record Create(@NotBlank @Pattern(regexp="[A-Z0-9]{6,12}") String stockCode,@NotNull TradeType tradeType,@NotNull @PastOrPresent Instant tradedAt,@NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=4) BigDecimal price,@Positive long quantity){}
 public record Update(@NotNull TradeType tradeType,@NotNull @PastOrPresent Instant tradedAt,@NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=4) BigDecimal price,@Positive long quantity,@PositiveOrZero long version){}
 public record Reason(@NotBlank @Size(max=40) String code,@Size(max=200) String customReason){}
 public record Journal(@Size(max=5000) String memo,@DecimalMin(value="0",inclusive=false) BigDecimal targetPrice,@DecimalMin(value="0",inclusive=false) BigDecimal stopLossPrice,@NotNull List<@Valid Reason> reasons,@PositiveOrZero long version){}
}
