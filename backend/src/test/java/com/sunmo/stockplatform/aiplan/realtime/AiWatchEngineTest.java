/*
 * AI 실시간 감시 엔진을 합성 틱으로 재현해요. 틱은 저장하지 않으므로 하루 장중 흐름을 테스트 안에서 만들어 넣어요.
 * 시가 범위(09:00~09:15: 고가 102, 저가 100) 뒤 09:15~09:20 5분봉이 거래량 5배로 102.6에 마감하면 매수 신호가 나는 흐름이 기본이에요.
 */
package com.sunmo.stockplatform.aiplan.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.EntryRequested;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.PositionChanged;
import com.sunmo.stockplatform.aiplan.realtime.AiWatchEngine.SignalChanged;
import com.sunmo.stockplatform.aiplan.realtime.WatchRules.Check;
import com.sunmo.stockplatform.aiplan.realtime.WatchRules.State;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 실시간 감시 엔진 테스트예요. */
class AiWatchEngineTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final TradingCostProperties COSTS = new TradingCostProperties(new BigDecimal("0.015"),
            new BigDecimal("0.015"), new BigDecimal("0.20"), new BigDecimal("0.08"), new BigDecimal("0.08"));
    private static final String EXAMPLE_CONDITIONS = """
            {"stockCode":"005930","entryStrategy":{"conditions":[
              {"metric":"RVOL_5M","operator":">=","value":null,"reference":null,"unit":"RATIO"},
              {"metric":"PRICE_VS_VWAP","operator":">","value":0,"reference":null,"unit":"KRW"},
              {"metric":"CLOSE_5M","operator":">","value":null,"reference":"OPENING_RANGE_15M_HIGH","unit":"KRW"}]}}""";

    private final List<AiWatchEngine.Effect> effects = new ArrayList<>();
    private long cumulativeVolume;
    private BigDecimal cumulativeValue = BigDecimal.ZERO, high, low, open;
    private long sequence;

    private static Instant at(String time) { return DAY.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant(); }

    private AiWatchEngine engine(String decision, String conditionsJson) throws Exception {
        var parsed = AiCondition.parse(new ObjectMapper().readTree(conditionsJson));
        var engine = new AiWatchEngine(DAY, decision, new BigDecimal("10000000"), COSTS);
        Map<LocalTime, BigDecimal> slots = new HashMap<>();
        for (LocalTime t = LocalTime.of(9, 0); t.isBefore(LocalTime.of(15, 30)); t = t.plusMinutes(5)) slots.put(t, new BigDecimal("1000"));
        engine.add(new StockMonitor.Pick(11, "005930", "삼성전자", 1, true, parsed.conditions(), parsed.supported()),
                new StockMonitor.Profile(slots, 20, new BigDecimal("100000"), new BigDecimal("105"), new BigDecimal("100")),
                true, parsed.unsupported());
        return engine;
    }

    /** 틱 하나를 넣기 전에 그 시각으로 1초 판단을 돌려요(실제 스케줄러처럼). */
    private void tick(AiWatchEngine engine, String time, String price, long volume) {
        Instant occurred = at(time);
        effects.addAll(engine.evaluate(occurred));
        BigDecimal p = new BigDecimal(price);
        if (open == null) open = p;
        high = high == null ? p : high.max(p);
        low = low == null ? p : low.min(p);
        cumulativeVolume += volume;
        cumulativeValue = cumulativeValue.add(p.multiply(BigDecimal.valueOf(volume)));
        var tick = new MarketTick("005930", DAY, occurred, p, volume, cumulativeVolume, cumulativeValue, ++sequence, open, high,
                low, null, null, null, null, false, null, null);
        effects.addAll(engine.accept(new ObservedMarketTick(tick, null, occurred.plusMillis(100))));
    }

    private void market(AiWatchEngine engine, String time, String kospiFromOpen) {
        BigDecimal value = new BigDecimal("2500").multiply(BigDecimal.ONE.add(new BigDecimal(kospiFromOpen).movePointLeft(2)));
        engine.market("KOSPI", value, new BigDecimal("2500"), at(time));
        engine.market("KOSDAQ", value, new BigDecimal("2500"), at(time));
    }

    /** 09:00~09:15 시가 범위(고가 102, 저가 100)를 평소 거래량(5분 1,000주)으로 만들고, 09:15~09:20에 돌파 봉을 만들어요. */
    private void openingAndBreakout(AiWatchEngine engine, String[] breakout) {
        String[][] bars = {
                { "100", "101.5", "100.5", "101", "100.5", "101", "100.5", "101", "100.5", "101" },
                { "101", "102", "100.5", "101.5", "101", "101.5", "101", "101.5", "101", "101.5" },
                { "101", "101.5", "100.5", "101", "101", "101", "101", "101", "101", "101" } };
        for (int bar = 0; bar < 3; bar++)
            for (int i = 0; i < 10; i++)
                tick(engine, LocalTime.of(9, bar * 5 + i / 2, i % 2 == 0 ? 5 : 35).toString(), bars[bar][i], 100);
        for (int i = 0; i < breakout.length; i++)
            tick(engine, LocalTime.of(9, 15 + i / 2, i % 2 == 0 ? 5 : 35).toString(), breakout[i], 500);
    }

    private static final String[] BREAKOUT = { "101.5", "101.8", "102", "102.2", "102.3", "102.4", "102.5", "102.5", "102.6", "102.6" };

    private List<SignalChanged> signals(String rule) {
        return effects.stream().filter(SignalChanged.class::isInstance).map(SignalChanged.class::cast)
                .filter(s -> s.rule().equals(rule)).toList();
    }

    private List<PositionChanged> positions() {
        return effects.stream().filter(PositionChanged.class::isInstance).map(PositionChanged.class::cast).toList();
    }

    /**
     * 기대: 돌파 5분봉이 닫힌 뒤(09:20:02 이후) 규칙 P와 A가 모두 BUY_CANDIDATE가 되고 매수 신호를 내요.
     * 호가 조회 뒤(arm) 처음 들어온 체결 가격 + 스프레드 절반으로 가상 진입하고, +3%에서 E2 익절, 손절가 아래에서 E1 손절해요.
     * 계획이 TRADE라 shadow가 아니에요.
     */
    @Test
    void entersAfterTheBreakoutBarClosesAndTracksBothExits() throws Exception {
        var engine = engine("TRADE", EXAMPLE_CONDITIONS);
        market(engine, "09:00:30", "0.4");
        openingAndBreakout(engine, BREAKOUT);
        assertThat(effects).noneMatch(EntryRequested.class::isInstance);
        market(engine, "09:19:50", "0.4");

        effects.addAll(engine.evaluate(at("09:20:03")));

        assertThat(signals("P")).last().satisfies(s -> {
            assertThat(s.to()).isEqualTo(State.BUY_CANDIDATE);
            assertThat(s.evaluatedAt()).isEqualTo(at("09:20:03"));
            assertThat(s.sourceAt()).isEqualTo(at("09:19:35"));
        });
        assertThat(signals("A")).last().extracting(SignalChanged::to).isEqualTo(State.BUY_CANDIDATE);
        assertThat(effects).filteredOn(EntryRequested.class::isInstance).hasSize(2);
        var view = engine.view().get(0);
        assertThat(view.checksP()).allSatisfy((name, check) -> assertThat(check).as(name).isEqualTo(Check.PASS));
        assertThat(view.indicators()).containsEntry("or15High", "102").containsEntry("or15Low", "100");

        // 판정 뒤, 호가 조회 전에 온 체결로는 진입하지 않아요.
        tick(engine, "09:20:04", "102.65", 100);
        assertThat(positions()).isEmpty();
        engine.arm("005930", "P", new BigDecimal("0.05"), "ORDERBOOK", at("09:20:05"));
        engine.arm("005930", "A", new BigDecimal("0.05"), "ORDERBOOK", at("09:20:05"));
        tick(engine, "09:20:10", "102.7", 100);

        assertThat(positions()).hasSize(2).allSatisfy(p -> {
            assertThat(p.opened()).isTrue();
            assertThat(p.position().shadow()).isFalse();
            assertThat(p.position().entryTickPrice()).isEqualByComparingTo("102.7");
            assertThat(p.position().entryPrice()).isEqualByComparingTo("102.7514");
            assertThat(p.position().quantity()).isEqualTo(97322);
            // 합성 자료의 1분 거래대금이 작아서 1,000만 원이 5%를 넘어요.
            assertThat(p.position().capacityLimited()).isTrue();
            assertThat(p.position().stopPrice()).isGreaterThan(new BigDecimal("101")).isLessThan(new BigDecimal("101.2"));
        });

        tick(engine, "09:30:05", "105.9", 100);
        tick(engine, "10:00:05", "101", 100);

        var p = engine.view().get(0).positions().stream().filter(x -> x.rule().equals("P")).findFirst().orElseThrow();
        assertThat(p.e2().reason()).isEqualTo("TAKE");
        assertThat(p.e2().at()).isEqualTo(at("09:30:05"));
        assertThat(p.e2().netPct()).isGreaterThan(new BigDecimal("2.5"));
        assertThat(p.e1().reason()).isEqualTo("STOP");
        assertThat(p.e1().price()).isEqualByComparingTo("100.9495");
        assertThat(p.e1().netPct()).isNegative();
        assertThat(p.maxPrice()).isEqualByComparingTo("105.9");
        assertThat(p.minPrice()).isEqualByComparingTo("101");
        // 하루 1회: 다시 조건이 맞아도 새로 진입하지 않아요.
        assertThat(effects).filteredOn(EntryRequested.class::isInstance).hasSize(2);
    }

    /** 기대: 계획이 WATCH_ONLY면 진입은 기록하되 shadow예요. 15:19에 남은 청산은 마지막 체결 가격으로 정리해요(CLOSE). */
    @Test
    void recordsShadowEntriesAndClosesOutAt1519() throws Exception {
        var engine = engine("WATCH_ONLY", EXAMPLE_CONDITIONS);
        market(engine, "09:19:50", "0.4");
        openingAndBreakout(engine, BREAKOUT);
        effects.addAll(engine.evaluate(at("09:20:03")));
        engine.arm("005930", "P", new BigDecimal("0.05"), "FALLBACK", at("09:20:04"));
        tick(engine, "09:20:10", "102.7", 100);
        tick(engine, "15:18:30", "103.5", 100);

        effects.addAll(engine.evaluate(at("15:19:00")));

        var p = engine.view().get(0).positions().get(0);
        assertThat(p.shadow()).isTrue();
        assertThat(p.spreadSource()).isEqualTo("FALLBACK");
        assertThat(p.e1().reason()).isEqualTo("CLOSE");
        assertThat(p.e2().reason()).isEqualTo("CLOSE");
        assertThat(p.e1().at()).isEqualTo(at("15:18:30"));
        // 규칙 A는 호가 조회(arm)를 끝내지 못해 진입하지 않았고, 15:19에 대기가 사라져요.
        assertThat(engine.view().get(0).positions()).hasSize(1);
    }

    /** 기대: 시장이 시가 대비 −0.5%보다 약하면 규칙 P는 CAUTION이고 매수 신호를 내지 않아요. */
    @Test
    void blocksEntriesWhenTheMarketIsWeak() throws Exception {
        var engine = engine("TRADE", EXAMPLE_CONDITIONS);
        openingAndBreakout(engine, BREAKOUT);
        market(engine, "09:19:50", "-0.8");
        effects.addAll(engine.evaluate(at("09:20:03")));

        assertThat(signals("P")).last().satisfies(s -> {
            assertThat(s.to()).isEqualTo(State.CAUTION);
            assertThat(s.reasons()).contains("MARKET:FAIL");
        });
        assertThat(effects).filteredOn(EntryRequested.class::isInstance).extracting(e -> ((EntryRequested) e).rule())
                .containsExactly("A");
    }

    /** 기대: 15분 고가보다 2% 넘게 올라간 뒤의 돌파는 추격이라 규칙 P는 READY에 머물러요. */
    @Test
    void doesNotChaseAnExtendedBreakout() throws Exception {
        var engine = engine("TRADE", EXAMPLE_CONDITIONS);
        openingAndBreakout(engine, new String[] { "102", "102.5", "103", "103.5", "103.8", "104", "104.2", "104.4", "104.5", "104.5" });
        market(engine, "09:19:50", "0.4");
        effects.addAll(engine.evaluate(at("09:20:03")));

        assertThat(engine.view().get(0).stateP()).isEqualTo(State.READY);
        assertThat(engine.view().get(0).checksP()).containsEntry("NOT_CHASING_BREAKOUT", Check.FAIL);
        assertThat(effects).filteredOn(EntryRequested.class::isInstance).extracting(e -> ((EntryRequested) e).rule())
                .doesNotContain("P");
    }

    /** 기대: 15분 시가 범위 뒤 5분봉 종가가 15분 저가(100) 아래로 마감하면 그날은 INVALIDATED로 남아요. */
    @Test
    void invalidatesWhenAFiveMinuteBarClosesBelowTheOpeningRange() throws Exception {
        var engine = engine("TRADE", EXAMPLE_CONDITIONS);
        market(engine, "09:00:30", "0.4");
        openingAndBreakout(engine, new String[] { "100.5", "100", "99.8", "99.6", "99.5", "99.5", "99.4", "99.4", "99.3", "99.3" });
        effects.addAll(engine.evaluate(at("09:20:03")));
        assertThat(engine.view().get(0).stateP()).isEqualTo(State.INVALIDATED);

        for (int i = 0; i < 10; i++) tick(engine, LocalTime.of(9, 20 + i / 2, i % 2 == 0 ? 5 : 35).toString(), "103", 500);
        effects.addAll(engine.evaluate(at("09:25:03")));

        assertThat(engine.view().get(0).stateP()).isEqualTo(State.INVALIDATED);
        assertThat(effects).noneMatch(EntryRequested.class::isInstance);
    }

    /** 기대: AI 조건에 계산할 수 없는 지표가 있으면 규칙 A는 UNSUPPORTED이고 판단하지 않아요. 규칙 P는 그대로 돌아요. */
    @Test
    void marksUnsupportedAiConditions() throws Exception {
        var engine = engine("TRADE", """
                {"stockCode":"005930","entryStrategy":{"conditions":[
                  {"metric":"SECTOR_STRENGTH","operator":">","value":1}]}}""");
        market(engine, "09:19:50", "0.4");
        openingAndBreakout(engine, BREAKOUT);
        effects.addAll(engine.evaluate(at("09:20:03")));

        var view = engine.view().get(0);
        assertThat(view.stateA()).isEqualTo(State.UNSUPPORTED);
        assertThat(view.unsupportedA()).containsExactly("지표 SECTOR_STRENGTH");
        assertThat(view.stateP()).isEqualTo(State.BUY_CANDIDATE);
        assertThat(signals("A")).isEmpty();
    }

    /** 기대: 거래정지 틱이 오면 1분을 기다리지 않고 바로 CAUTION이에요. */
    @Test
    void reactsToHaltsImmediately() throws Exception {
        var engine = engine("TRADE", EXAMPLE_CONDITIONS);
        tick(engine, "09:00:05", "100", 100);
        cumulativeVolume += 10;
        var halted = new MarketTick("005930", DAY, at("09:00:20"), new BigDecimal("100"), 10, cumulativeVolume,
                cumulativeValue.add(new BigDecimal("1000")), ++sequence, open, high, low, null, null, null, null, true, null, null);
        effects.addAll(engine.accept(new ObservedMarketTick(halted, null, at("09:00:20").plusMillis(100))));

        assertThat(signals("P")).singleElement().satisfies(s -> {
            assertThat(s.to()).isEqualTo(State.CAUTION);
            assertThat(s.reasons()).containsExactly("HALTED");
        });
    }
}
