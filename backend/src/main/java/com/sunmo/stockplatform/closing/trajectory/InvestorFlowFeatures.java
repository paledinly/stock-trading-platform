/*
 * 수집된 수급(외국인·기관·프로그램 순매수) 관측으로 스냅샷용 수급 특징값을 계산하는 도구 클래스예요.
 * 기준시각 시점의 당일 누적 순매수와 30분 전 대비 변화량을 구하고, 각 값의 범위·수신시각·원천시각을 함께 남겨요.
 * TrajectoryFeatures.calculate가 스냅샷의 investorFlow 항목을 채울 때 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import java.time.*;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/** Receipt-window changes, not inferred continuous investor trades. */
/*
 * (수신 시점 사이의 변화량일 뿐, 연속된 투자자 매매를 추정한 값이 아니에요.)
 * 은행 잔고를 두 번 조회해 차이를 보는 것처럼, 누적 순매수를 두 시점에 본 차이만 계산해요. 그 사이 거래 내역은 알 수 없어요.
 * 개인 순매수와 과거 3·5일 수급은 수집하지 않아 비워 둬요.
 */
public final class InvestorFlowFeatures {
    /** 도구 클래스라 객체를 만들지 못하게 막아 둔 생성자예요. */
    private InvestorFlowFeatures() {}
    /**
     * 하는 일: 외국인·기관(KIS 추정치)과 프로그램(KRX) 각각의 당일 누적 순매수와 30분 변화량을 계산해요.
     * 받는 것: 종목, 맥락 관측 목록, 기준시각 cutoff, 입력 허용 마감, 관측 최대 나이예요.
     * 돌려주는 것: 읽기 전용 Map이에요. 기준 시점 관측이 없으면 값은 null, 상태는 UNAVAILABLE이에요. 단위는 주(SHARES)예요.
     */
    public static Map<String, Object> calculate(String symbol, List<Context> rows, Instant cutoff,
            Instant availableBy, Duration maxAge) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String party : List.of("foreign", "institution", "program")) {
            String kind = party.toUpperCase(Locale.ROOT) + "_NET_BUY";
            String scope = party.equals("program") ? "KRX" : "KIS_ESTIMATE_ALL";
            Context current = at(rows, kind, symbol, scope, cutoff, availableBy, maxAge);
            Context base = at(rows, kind, symbol, scope, cutoff.minusSeconds(1800), availableBy, maxAge);
            result.put(party + "NetBuyToday", current == null ? null : current.value());
            result.put(party + "NetBuy30mDelta", current == null || base == null ? null : current.value().subtract(base.value()));
            result.put(party + "Observation", fields("scope", scope, "unit", "SHARES",
                    "status", current == null ? "UNAVAILABLE" : current.status(),
                    "receivedAt", current == null ? null : current.receivedAt(),
                    "sourceAt", current == null ? null : current.sourceAt(),
                    "baselineReceivedAt", base == null ? null : base.receivedAt(),
                    "deltaMeaning", "CHANGE_IN_OBSERVED_CUMULATIVE_VALUES"));
        }
        result.put("retailNetBuyToday", null);
        result.put("historical3d5dStatus", "NOT_COLLECTED");
        return Collections.unmodifiableMap(result);
    }
    /**
     * 하는 일: 지정 시각(at) 이하에 받았고, 입력 마감 전이며, maxAge 이내로 신선한 같은 날 관측 중 가장 늦게 받은 것을 골라요.
     * 원천시각이 있으면 수신시각보다 미래가 아니고 너무 오래되지 않아야 해요.
     * 받는 것: 관측 목록, 종류, 종목, 범위, 기준 시각, 입력 마감, 최대 나이예요. 돌려주는 것: Context 또는 null이에요.
     */
    private static Context at(List<Context> rows, String kind, String symbol, String scope, Instant at,
            Instant available, Duration maxAge) {
        LocalDate day = available.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        return rows.stream().filter(r -> r.kind().equals(kind) && r.symbol().equals(symbol) && r.scope().equals(scope)
                && r.value() != null && "SHARES".equals(r.unit())
                && r.receivedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().equals(day)
                && !r.receivedAt().isAfter(at) && !r.receivedAt().isAfter(available)
                && !r.receivedAt().isBefore(at.minus(maxAge))
                && (r.sourceAt() == null || !r.sourceAt().isAfter(r.receivedAt())
                    && !r.sourceAt().isBefore(at.minus(maxAge))))
                .max(Comparator.comparing(Context::receivedAt)).orElse(null);
    }
}
