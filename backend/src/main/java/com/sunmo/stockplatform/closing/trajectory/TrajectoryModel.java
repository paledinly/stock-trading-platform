/*
 * Trajectory(장 후반 시간에 따른 종목 궤적) 연구 데이터의 값 모양(record)을 모아 둔 파일이에요.
 * 1분 집계(Minute), 지수·순위·수급 같은 맥락 관측(Context), 특정 시각의 특징값 묶음(Snapshot)을 정의해요.
 * TrajectoryStore가 이 모양을 JSON으로 저장·복원하고, 수집기·계산기·API가 함께 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Trajectory 데이터 상자들의 모음 클래스예요. 직접 객체로 만들지는 않아요.
 * 연구 노트의 양식 모음집처럼 "1분 기록지, 주변 상황 기록지, 시점별 요약지" 양식을 한곳에 모아 뒀어요.
 */
public final class TrajectoryModel {
    /** 양식 모음용 클래스라 객체를 만들지 못하게 막아 둔 생성자예요. */
    private TrajectoryModel() {}
    /**
     * 한 종목의 1분 집계 한 줄이에요. start는 분 시작, occurredAt은 마지막 틱 원천 발생시각, receivedAt은 마지막 수신시각,
     * finalizedAt은 확정 요청 시각이에요. 금액=원, 수량=주이고, 자료가 온전하지 않으면 complete=false예요.
     */
    public record Minute(String symbol, Instant start, Instant occurredAt, Instant receivedAt, Instant finalizedAt,
            String phase, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            BigDecimal volume, BigDecimal turnover, BigDecimal dailyVolume, BigDecimal dailyTurnover,
            BigDecimal dayOpen, BigDecimal dayHigh, BigDecimal dayLow, BigDecimal executionStrength,
            BigDecimal buyVolume, BigDecimal sellVolume, boolean complete) {}
    /** REST observations use receipt time; no exchange timestamp is invented. Scope prevents mixing rank universes. */
    /*
     * (REST 관측은 수신시각만 써요. 거래소 시각을 지어내지 않아요. scope는 ALL/개별 시장 순위가 섞이지 않게 해요.)
     * kind는 INDEX, TURNOVER_RANK, *_NET_BUY 같은 종류, value는 값, returnPct는 등락률(퍼센트), sourceAt은 원천시각(있을 때만)이에요.
     */
    public record Context(String kind, String symbol, String scope, Instant receivedAt,
            BigDecimal value, BigDecimal returnPct, Instant sourceAt, String status, String unit) {
        /** 하는 일: 원천시각·상태·단위 없이 맥락 관측을 만들어요. 받는 것: 종류, 종목/지수, 범위, 수신시각, 값, 등락률. 돌려주는 것: Context예요. */
        public Context(String kind, String symbol, String scope, Instant receivedAt, BigDecimal value, BigDecimal returnPct) {
            this(kind, symbol, scope, receivedAt, value, returnPct, null, null, null);
        }
    }
    /**
     * 한 종목의 특정 기준시각 특징값 묶음이에요. timestamp는 원천 정보 cutoff(이 시각까지의 정보만 사용),
     * inputAvailableBy는 입력 수신/확정 검사시각, evaluatedAt은 계산 완료시각이에요.
     * 미수집 분야(업종·테마·뉴스·공시 등)는 비어 있거나 null일 수 있어요.
     */
    public record Snapshot(String symbol, Instant timestamp, Instant evaluatedAt, Instant inputAvailableBy, String version,
            Map<String, Object> price, Map<String, Object> volume, Map<String, Object> turnover,
            Map<String, Object> execution, Map<String, Object> vwap, Map<String, Object> technical,
            Map<String, Object> intradayPattern, Map<String, Object> market,
            Map<String, Object> subScores, Map<String, Object> risk,
            Map<String, Object> orderbook, Map<String, Object> closingAuction,
            Map<String, Object> investorFlow, Map<String, Object> sector, Map<String, Object> theme,
            List<Object> news, List<Object> disclosures, Map<String, Object> availability,
            TrajectoryProperties policy) {
        /** 하는 일: 계산 완료시각(evaluatedAt)만 바꾼 복사본을 만들어요. 받는 것: 완료 시각. 돌려주는 것: 새 Snapshot이에요. */
        public Snapshot completedAt(Instant completed) {
            return new Snapshot(symbol, timestamp, completed, inputAvailableBy, version, price, volume, turnover, execution,
                    vwap, technical, intradayPattern, market, subScores, risk, orderbook, closingAuction,
                    investorFlow, sector, theme, news, disclosures, availability, policy);
        }
    }
    /**
     * 하는 일: "이름, 값, 이름, 값…" 순서의 인자를 넣은 순서가 유지되는 읽기 전용 Map으로 만들어요.
     * 받는 것: 짝수 개 인자(이름은 문자열)예요. 돌려주는 것: 수정 불가 Map이에요. 값은 null이어도 돼요.
     */
    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(result);
    }
}
