/*
 * AI 장전 계획 2단계(실시간 감시)의 고정 규칙 P 임계값과 상태 이름을 모아 둔 파일이에요.
 * 값은 모두 검증 전 가설(HYPOTHESIS)이고, 2026-11-10 기록 시작 전에 정했어요. 기록 중에는 바꾸지 않아요.
 * 바꿔야 하면 RULE_VERSION을 올리고 그날부터 따로 집계해요(docs/AI_PREMARKET_PHASE2.md).
 */
package com.sunmo.stockplatform.aiplan.realtime;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

/** 규칙 P 임계값이에요. 시각은 모두 정규장 기준 시각(MarketSessionHours.regularTime)이에요. */
public final class WatchRules {
    public static final String RULE_VERSION = "ai-watch-p1";

    /** 종목별 감시 상태예요. 우선순위는 INVALIDATED > CAUTION > BUY_CANDIDATE > READY > WATCH. */
    public enum State { NOT_SUBSCRIBED, WATCH, READY, BUY_CANDIDATE, CAUTION, INVALIDATED, EXPIRED, UNSUPPORTED }

    /** 조건 하나의 판정이에요. UNAVAILABLE은 자료가 없어 판단할 수 없다는 뜻이고 충족으로 보지 않아요. */
    public enum Check { PASS, FAIL, UNAVAILABLE }

    // 시가 범위(최초 5분·15분)가 끝나는 시각, 신규 진입 시간, 가상 청산 시각, 감시 종료 시각
    static final LocalTime OPENING_RANGE_5_END = LocalTime.of(9, 5);
    static final LocalTime OPENING_RANGE_15_END = LocalTime.of(9, 15);
    static final LocalTime ENTRY_START = LocalTime.of(9, 15);
    static final LocalTime ENTRY_END = LocalTime.of(14, 30);
    static final LocalTime EXIT_AT = LocalTime.of(15, 19);
    static final LocalTime SESSION_END = LocalTime.of(15, 20);

    // 거래량: 같은 5분 칸 과거 평균 대비 2배 이상. 과거 칸이 10일 미만이면 전일 5분 평균 거래대금 × 2로 대신 봐요.
    static final BigDecimal MIN_RVOL = new BigDecimal("2.0");
    static final BigDecimal FALLBACK_TURNOVER_MULTIPLE = new BigDecimal("2.0");
    static final int RVOL_DAYS = 20;
    static final int RVOL_MIN_DAYS = 10;

    // 추격 금지: 시가 대비 +8% 이하, 직전 완료 5분봉 상승 2% 미만, 15분 시가 범위 고가 대비 +2% 이하
    static final BigDecimal MAX_FROM_OPEN_PCT = new BigDecimal("8");
    static final BigDecimal MAX_LAST_5M_GAIN_PCT = new BigDecimal("2");
    static final BigDecimal MAX_ABOVE_BREAKOUT_PCT = new BigDecimal("2");

    // 손절 = max(15분 시가 범위 저가, VWAP − 0.5 × ATR5m). 손절 거리는 ATR5m 이상이고 0.8% 이상이어야 해요.
    static final BigDecimal STOP_VWAP_ATR = new BigDecimal("0.5");
    static final BigDecimal MIN_STOP_ATR = BigDecimal.ONE;
    static final BigDecimal MIN_STOP_PCT = new BigDecimal("0.8");
    static final int ATR_BARS = 12;
    static final int ATR_MIN_BARS = 3;

    // 시장: KOSPI·KOSDAQ 둘 다 시가 대비 −0.5% 이상. 지수 값이 3분 넘게 오래되면 판단하지 않아요.
    static final BigDecimal MARKET_MIN_FROM_OPEN_PCT = new BigDecimal("-0.5");
    static final Duration MARKET_STALE = Duration.ofMinutes(3);

    // READY: 15분 시가 범위 고가 1% 이내로 다가오고 VWAP 위
    static final BigDecimal READY_NEAR_PCT = new BigDecimal("1");

    // 자료 품질: 최근 5분 안에 끊김(dataLoss·누적 거래량 불일치)이 있거나 마지막 틱 수신이 60초 넘게 지났으면 매수 금지
    static final Duration DATA_GAP_LOOKBACK = Duration.ofMinutes(5);
    static final Duration DATA_MAX_SILENCE = Duration.ofSeconds(60);
    // 1분·5분 칸은 끝난 뒤 2초를 더 기다렸다가 닫아요(늦게 온 틱은 닫힌 칸에 넣지 않아요).
    static final Duration BAR_WATERMARK = Duration.ofSeconds(2);

    // 청산 E2(기존 앱 규칙): +3% 익절 / −2% 손절
    static final BigDecimal E2_TAKE_PCT = new BigDecimal("3");
    static final BigDecimal E2_STOP_PCT = new BigDecimal("-2");

    // 호가 조회에 실패하면 스프레드 절반을 0.06%로 가정해요. 1회 금액이 직전 1분 거래대금의 5%를 넘으면 체결 규모 경고.
    static final BigDecimal FALLBACK_HALF_SPREAD_PCT = new BigDecimal("0.06");
    static final BigDecimal CAPACITY_SHARE = new BigDecimal("0.05");

    private WatchRules() { }
}
