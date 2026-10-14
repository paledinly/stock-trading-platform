/*
 * ClosingPaperEntryService 가 마감 추천 평가 완료 후 15:20 전에 신선한 체결을 보고 가상 진입을 기록하는 규칙을 확인해요.
 * 완료 전 체결·유실 표시 체결·미래 체결로 진입하거나, 마감 뒤에 소급해 진입을 채우면 성과가 실제로 불가능한 가격으로 계산되므로 중요해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 시험 감독관처럼 가상 진입이 정해진 시간 창과 신선도 조건을 정확히 지키는지 확인하는 테스트예요. */
class ClosingPaperEntryServiceTest {
    /** 평가 완료 시각이에요(한국시간 15:15:10). */
    static final Instant DONE = Instant.parse("2026-10-07T06:15:10Z");
    /** 가상 진입 마감 시각이에요(한국시간 15:20:00, 이 시각 미만이어야 진입 가능). */
    static final Instant DEADLINE = Instant.parse("2026-10-07T06:20:00Z");
    /** 현재 시각과 거래일 여부를 조절하는 달력 목(mock, 가짜 객체)이에요. */
    final ClosingTradingCalendar clock = mock(ClosingTradingCalendar.class);
    final ClosingRecommendationProperties policy = new ClosingRecommendationProperties(20,4,null,null,null,null,null);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;
    ClosingPaperEntryService service;
    /** H2(테스트용 메모리 DB)에 V30 가상 진입 테이블을 만들고, 현재 시각을 완료 5초 뒤로 둬요. */
    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table closing_recommendation_run(id bigint primary key)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V30__closing_paper_entry.sql")).execute(db);
        jdbc.update("insert into closing_recommendation_run values (1)");
        service = new ClosingPaperEntryService(jdbc, clock, policy);
        when(clock.isTradingDay(any())).thenReturn(true);
        when(clock.now()).thenReturn(DONE.plusSeconds(5));
    }
    /** 테스트마다 메모리 DB 를 닫아요. */
    @AfterEach void close() { db.shutdown(); }
    /** 주어진 시각에 완료된 2026-10-07 추천 실행(run id 1) 목을 만들어요. */
    ClosingRecommendationRun run(Instant completed) {
        var run = mock(ClosingRecommendationRun.class);
        when(run.getId()).thenReturn(1L); when(run.getCompletedAt()).thenReturn(completed);
        when(run.getRecommendationDate()).thenReturn(LocalDate.of(2026,10,7));
        return run;
    }
    /** 원천 발생시각 source, 수신시각 received, 유실 의심 여부 loss 를 가진 005930 체결을 만들어요. */
    ObservedMarketTick tick(Instant source, Instant received, boolean loss) {
        var tick = new MarketTick("005930", LocalDate.of(2026,10,7), source, BigDecimal.valueOf(100),
                1,100,BigDecimal.valueOf(10000),1,null,null,null,null,null,null,null,false,null,null);
        return new ObservedMarketTick(tick, null, received, loss);
    }
    /**
     * 무엇: 완료 직후 받은 신선한 체결로 한 번만 진입하고, 서비스를 다시 만들어도 같은 기록이 남는지 확인해요.
     * 입력: 완료 1초 뒤 발생·2초 뒤 수신한 체결, 현재 시각은 완료 5초 뒤.
     * 기대: 상태 ENTERED, 진입 시각은 확인 시점(완료 5초 뒤), 재시작 후 조회 결과가 같아요.
     */
    @Test void actualFreshReceiptIsSavedOnceAndSurvivesRestart() {
        service.prepare(run(DONE),List.of("005930"));
        service.receive(tick(DONE.plusSeconds(1),DONE.plusSeconds(2),false)); service.capture();
        var entry = service.find(1,"005930");
        assertThat(entry.status()).isEqualTo("ENTERED"); assertThat(entry.at()).isEqualTo(DONE.plusSeconds(5));
        var restarted = new ClosingPaperEntryService(jdbc,clock,policy); restarted.capture();
        assertThat(restarted.find(1,"005930")).isEqualTo(entry);
    }
    /**
     * 무엇: 진입에 쓸 수 없는 체결은 거르고, 마감까지 신선한 체결이 없으면 미진입 상태로 끝나는지 확인해요.
     * 입력: 완료 시각과 같은 시각 체결, 유실 의심 체결, 현재보다 미래 체결, 그리고 원천→확인 지연이 11초인 체결.
     * 기대: 어느 체결로도 가격이 정해지지 않고, 지연 10초 초과는 executable=false, 15:20 이 되면 NO_FRESH_TICK_BEFORE_DEADLINE 이에요.
     */
    @Test void stalePreCompletionLostAndFutureTicksCannotFillAndDeadlineExpires() {
        service.prepare(run(DONE),List.of("005930"));
        for (var event : List.of(tick(DONE,DONE.plusSeconds(1),false),
                tick(DONE.plusSeconds(1),DONE.plusSeconds(2),true),
                tick(DONE.plusSeconds(30),DONE.plusSeconds(30),false))) {
            service.receive(event); service.capture(); assertThat(service.find(1,"005930").price()).isNull();
        }
        // 원천 발생(완료+1초)부터 확인 시각(완료+12초)까지 11초라 지연 한도(10초로 보여요)를 넘어요.
        assertThat(ClosingPaperEntryService.executable(tick(DONE.plusSeconds(1),DONE.plusSeconds(2),false),
                DONE,DONE.plusSeconds(12),DEADLINE)).isFalse();
        when(clock.now()).thenReturn(DEADLINE); service.capture();
        assertThat(service.find(1,"005930").status()).isEqualTo("NO_FRESH_TICK_BEFORE_DEADLINE");
    }
    /**
     * 무엇: 평가가 진입 마감(15:20) 시각에야 끝나면 진입을 소급하지 않고 마감 놓침으로 기록하는지 확인해요.
     * 입력: 15:20:00 에 완료된 추천 실행.
     * 기대: 상태 EVALUATION_DEADLINE_MISSED.
     * ⚠️ 확인 필요: 메서드 이름에 "OfflineRestart" 가 있지만, 재시작(오프라인 후 재기동) 상황은 이 테스트에서 따로 다루지 않는 것으로 보여요.
     */
    @Test void lateEvaluationAndOfflineRestartDoNotBackfillEntry() {
        service.prepare(run(DEADLINE),List.of("005930"));
        assertThat(service.find(1,"005930").status()).isEqualTo("EVALUATION_DEADLINE_MISSED");
    }
}
