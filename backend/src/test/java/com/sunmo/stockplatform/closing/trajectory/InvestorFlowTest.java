/*
 * InvestorFlowCollector 의 응답 파싱과 InvestorFlowFeatures 의 수급(외국인·기관·프로그램 순매수) 특징 계산을 확인해요.
 * 결측을 0 으로 바꾸거나, 미래·오래된·다른 출처·전일 관측을 섞으면 point-in-time(그 시점에 알 수 있던 정보만 쓰기) 규칙이 깨지므로 중요해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/** 수급 데이터가 시각·출처 규칙을 지키며 특징으로 바뀌는지 확인하는 테스트예요. */
class InvestorFlowTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    /** 기준 시각이에요(UTC 06:00 = 한국시간 15:00). */
    private static final Instant NOW = Instant.parse("2026-10-02T06:00:00Z");
    /** 원천 시각을 모르는(수신 시각만 있는) 005930 수급 맥락 행을 만들어요. */
    private static Context row(String kind, String scope, Instant receipt, String value) {
        return new Context(kind, "005930", scope, receipt, new BigDecimal(value), null, null, "ESTIMATED_SOURCE_TIME_UNKNOWN", "SHARES");
    }
    /**
     * 무엇: 추정 순매수 파싱이 부호·천단위 쉼표를 보존하고, 빈 값은 0 으로 만들지 않으며, 대상 종목만 고르는지 확인해요.
     * 입력: 외국인 "-1,200"·기관 "" 인 005930 과 대상 밖 000660 배열, 그리고 배열이 아닌 {}.
     * 기대: 1행(-1200, 원천 시각 null, 추정 상태)만 나오고, {} 는 IllegalArgumentException 이에요.
     */
    @Test void estimatesPreserveSignedQuantityAndMissingIsNotZero() throws Exception {
        var rows = InvestorFlowCollector.parseEstimates(mapper.readTree("""
                [{"mksc_shrn_iscd":"005930","frgn_ntby_qty":"-1,200","orgn_ntby_qty":""},
                 {"mksc_shrn_iscd":"000660","frgn_ntby_qty":"900","orgn_ntby_qty":"800"}]
                """), Set.of("005930"), NOW);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().value()).isEqualByComparingTo("-1200");
        assertThat(rows.getFirst().sourceAt()).isNull();
        assertThat(rows.getFirst().status()).isEqualTo("ESTIMATED_SOURCE_TIME_UNKNOWN");
        assertThatThrownBy(() -> InvestorFlowCollector.parseEstimates(mapper.readTree("{}"), Set.of("005930"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
    /**
     * 무엇: 프로그램 매매 응답 순서와 관계없이, 기준 시각을 넘지 않는 가장 최근 행을 고르는지 확인해요.
     * 입력: 14:59, 15:01(미래), 14:50 행이 섞인 응답과 빈 배열.
     * 기대: 14:59 행(값 100, 원천 시각 NOW-60초, 수신 시각 NOW)을 쓰고, 빈 배열은 null 이에요.
     */
    @Test void programUsesLatestNonFutureSourceRegardlessOfResponseOrder() throws Exception {
        var row = InvestorFlowCollector.parseProgram(mapper.readTree("""
                [{"bsop_hour":"145900","whol_smtn_ntby_qty":"100"},
                 {"bsop_hour":"150100","whol_smtn_ntby_qty":"999"},
                 {"bsop_hour":"145000","whol_smtn_ntby_qty":"-200"}]
                """), "005930", NOW);
        assertThat(row.value()).isEqualByComparingTo("100");
        assertThat(row.sourceAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(row.receivedAt()).isEqualTo(NOW);
        assertThat(InvestorFlowCollector.parseProgram(mapper.readTree("[]"), "005930", NOW)).isNull();
    }
    /**
     * 무엇: 30분 변화량 등 특징 계산에서 미래·오래된·다른 출처(scope)·전일 관측을 제외하는지 확인해요.
     * 입력: 30분 전(-100), 현재(50), 1초 뒤 미래(9999), 다른 출처 KRX(7777) 행, 최대 허용 경과 6분.
     * 기대: 30분 변화량은 150 이고, 기관 값·오래된 값만 있는 경우·기준값 없는 변화량·전일 값·원천이 15분 지난 값은 모두 null 이에요.
     */
    @Test void deltaRejectsFutureStaleOtherScopeAndPriorDayObservations() {
        var old = row("FOREIGN_NET_BUY", "KIS_ESTIMATE_ALL", NOW.minusSeconds(1800), "-100");
        var current = row("FOREIGN_NET_BUY", "KIS_ESTIMATE_ALL", NOW, "50");
        var future = row("FOREIGN_NET_BUY", "KIS_ESTIMATE_ALL", NOW.plusSeconds(1), "9999");
        var other = row("FOREIGN_NET_BUY", "KRX", NOW, "7777");
        var f = InvestorFlowFeatures.calculate("005930", List.of(future, old, other, current), NOW, NOW.plusSeconds(10), Duration.ofMinutes(6));
        assertThat((BigDecimal) f.get("foreignNetBuy30mDelta")).isEqualByComparingTo("150");
        assertThat(f.get("institutionNetBuyToday")).isNull();
        // 아래는 각각 오래된 값만 있음, 30분 전 기준값 없음, 다음날 조회(전일 관측), 원천 시각이 오래된 경우예요.
        assertThat(InvestorFlowFeatures.calculate("005930", List.of(old), NOW, NOW, Duration.ofMinutes(6)).get("foreignNetBuyToday")).isNull();
        assertThat(InvestorFlowFeatures.calculate("005930", List.of(current), NOW, NOW, Duration.ofMinutes(6)).get("foreignNetBuy30mDelta")).isNull();
        assertThat(InvestorFlowFeatures.calculate("005930", List.of(current), NOW.plusSeconds(86400), NOW.plusSeconds(86400), Duration.ofDays(2)).get("foreignNetBuyToday")).isNull();
        Context staleSource = new Context("PROGRAM_NET_BUY", "005930", "KRX", NOW, BigDecimal.TEN, null, NOW.minusSeconds(900), "INTRADAY", "SHARES");
        assertThat(InvestorFlowFeatures.calculate("005930", List.of(staleSource), NOW, NOW, Duration.ofMinutes(6)).get("programNetBuyToday")).isNull();
    }
    /**
     * 무엇: 수급 값을 스냅샷에 담아도 점수(subScores)는 바뀌지 않고, 새 필드가 없는 예전 JSON 도 읽히는지 확인해요.
     * 입력: 같은 분봉으로 수급 없이/있이 계산한 두 스냅샷, sourceAt 이 없는 예전 지수 맥락 JSON.
     * 기대: 두 스냅샷의 subScores 가 같고 외국인 순매수 30 이 기록되며, 예전 JSON 의 sourceAt 은 null 이에요.
     */
    @Test void snapshotIncludesFlowsWithoutChangingScoresAndOldContextJsonStillLoads() throws Exception {
        var minute = TrajectoryFeaturesTest.row(0, "105", "100", "120");
        Instant cutoff = minute.start().plusSeconds(60);
        var base = TrajectoryFeatures.calculate("005930", "KOSPI", cutoff, cutoff.plusSeconds(2), List.of(minute), List.of(), List.of(), TrajectoryFeaturesTest.policy());
        var flows = List.of(row("FOREIGN_NET_BUY", "KIS_ESTIMATE_ALL", cutoff.minusSeconds(1), "30"));
        var enriched = TrajectoryFeatures.calculate("005930", "KOSPI", cutoff, cutoff.plusSeconds(2), List.of(minute), flows, List.of(), TrajectoryFeaturesTest.policy());
        assertThat(enriched.subScores()).isEqualTo(base.subScores());
        assertThat((BigDecimal) enriched.investorFlow().get("foreignNetBuyToday")).isEqualByComparingTo("30");
        Context old = mapper.readValue("""
                {"kind":"INDEX","symbol":"KOSPI","scope":"KOSPI","receivedAt":"2026-10-02T06:00:00Z","value":2500,"returnPct":1}
                """, Context.class);
        assertThat(old.sourceAt()).isNull();
    }
}
