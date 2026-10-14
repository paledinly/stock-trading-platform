/*
 * 거래 기록 API가 받는 요청 본문(JSON) 모양과 입력 검증 규칙을 모아 둔 파일이에요.
 * 잘못된 값(빈 종목코드, 0 이하 가격, 미래 시각 등)은 서비스에 닿기 전에 걸러져요.
 * TradeController와 TradeService가 사용해요.
 */
package com.sunmo.stockplatform.trade.api;

import com.sunmo.stockplatform.trade.domain.TradeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 거래 관련 요청 양식들을 한곳에 모은 묶음 클래스예요.
 * 관공서 서류함에 "신규 신청서 / 변경 신청서 / 메모 첨부서" 양식을 칸칸이 꽂아 둔 것과 같아요. 직접 객체로 만들지는 않아요.
 */
public final class TradeRequests {
    /** 양식 묶음일 뿐이라 객체를 만들지 못하게 막아 둔 생성자예요. */
    private TradeRequests() {
    }

    /**
     * 새 거래 기록 요청이에요.
     * 종목코드(영문 대문자/숫자 6~12자), 매수/매도 구분, 거래 시각(현재 또는 과거), 가격(0 초과), 수량(양수)을 받아요.
     */
    public record Create(@NotBlank @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode, @NotNull TradeType tradeType,
            @NotNull @PastOrPresent Instant tradedAt,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 4) BigDecimal price,
            @Positive long quantity) {
    }

    /**
     * 거래 수정 요청이에요. 종목은 바꿀 수 없어요.
     * version은 낙관적 잠금(내가 본 뒤 남이 먼저 고쳤는지 버전 번호로 확인하는 방식)에 쓰여요.
     */
    public record Update(@NotNull TradeType tradeType, @NotNull @PastOrPresent Instant tradedAt,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 4) BigDecimal price,
            @Positive long quantity, @PositiveOrZero long version) {
    }

    /**
     * 매매 이유 한 개예요. code는 이유 분류(최대 40자), customReason은 직접 쓴 설명(최대 200자)이에요.
     * code가 CUSTOM이면 서비스에서 customReason을 반드시 요구해요.
     */
    public record Reason(@NotBlank @Size(max = 40) String code, @Size(max = 200) String customReason) {
    }

    /**
     * 투자 노트 저장 요청이에요.
     * 메모(최대 5000자), 목표가·손절가(선택, 0 초과), 이유 목록(필수, 빈 목록 가능), 버전(동시 수정 확인용)을 받아요.
     */
    public record Journal(@Size(max = 5000) String memo,
            @DecimalMin(value = "0", inclusive = false) BigDecimal targetPrice,
            @DecimalMin(value = "0", inclusive = false) BigDecimal stopLossPrice, @NotNull List<@Valid Reason> reasons,
            @PositiveOrZero long version) {
    }
}
