/*
 * 마감 추천 run 테이블을 만드는 DB 마이그레이션(Flyway, 스키마 버전 변경 도구)을 실제 PostgreSQL에서 검사하는 테스트예요.
 * 예전(V21까지) 데이터가 업그레이드 후에도 성과·판단 기록 참조를 잃지 않는지, 같은 요청 키의 동시 재시도가 run 1개로 모이는지 확인해요.
 * 운영 DB를 올릴 때 과거 추천 기록이 끊기거나 중복 run이 생기면 성과 추적이 깨지기 때문에 중요해요.
 */
package com.sunmo.stockplatform.closing;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 이삿짐 점검원처럼 스키마를 옮긴 뒤에도 기존 추천 기록과 연결이 그대로인지 확인하는 테스트예요.
 * Docker가 없으면 건너뛰어요(Testcontainers, 테스트용 컨테이너를 띄우는 도구).
 */
@Testcontainers(disabledWithoutDocker = true)
class ClosingRunMigrationTest {
    /** 테스트마다 쓰는 실제 PostgreSQL 17 컨테이너예요. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /**
     * 업그레이드가 기존 참조를 보존하고, 같은 요청 키의 동시 재시도가 직렬화되는지 확인해요.
     * 준비: V21까지 마이그레이션한 뒤 종목, 옛 추천, 다음날 성과, 보유 판단을 1건씩 넣고 최신 버전까지 올려요.
     * 기대: 옛 탐지에는 수신시각을 지어내지 않고(received_at 모두 null), 옛 추천은 LEGACY run에 묶이며
     * 성과(close_price 101)와 판단 1건이 남아요. 새 REPLAY run에 추천을 추가할 수 있어요.
     * 같은 request_key로 두 스레드가 동시에 run을 만들면 같은 id를 받아 1행만 생기고, 롤백된 트랜잭션은 행을 남기지 않아요.
     */
    @Test
    void upgradePreservesReferencesAndConcurrentRetriesSerialize() throws Exception {
        // 준비: V21 스키마에 옛 데이터를 넣어요.
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("21").load().migrate();
        Long stock = jdbc.queryForObject("""
                insert into stock(stock_code,stock_name,market,market_type,master_synced_at)
                values ('TEST01','Migration test','KOSPI','COMMON',now()) returning id
                """, Long.class);
        Long original = jdbc.queryForObject("""
                insert into closing_recommendation(recommendation_date,generated_at,stock_id,rank_no,
                  recommendation_score,buy_reference_price,recommendation_reason,risk_reason,strategy_version,status,candidate_observed_at)
                values ('2026-09-16',now(),?,1,80,100,'{}','{}','legacy-test','CANDIDATE',now()) returning id
                """, Long.class, stock);
        jdbc.update("""
                insert into overnight_performance(closing_recommendation_id,evaluated_at,status,calculation_version,close_price)
                values (?,now(),'COMPLETED','overnight-performance-v1',101)
                """, original);
        jdbc.update("""
                insert into overnight_position_decision(closing_recommendation_id,evaluated_at,decision,reason_json,calculation_version)
                values (?,now(),'HOLD','{}','legacy-test')
                """, original);
        // 실행: 최신 버전까지 마이그레이션하고 검증해요.
        var flyway = Flyway.configure().dataSource(source).load();
        flyway.migrate();
        flyway.validate();
        assertThat(jdbc.queryForObject("select count(*) from scanner_detection where received_at is not null", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select execution_mode from closing_recommendation_run a join closing_recommendation r on r.run_id=a.id where r.id=?",
                String.class, original)).isEqualTo("LEGACY");
        assertThat(jdbc.queryForObject("select close_price from overnight_performance where closing_recommendation_id=?",
                Integer.class, original)).isEqualTo(101);
        assertThat(jdbc.queryForObject("select count(*) from overnight_position_decision where closing_recommendation_id=?",
                Integer.class, original)).isEqualTo(1);
        Long newRun = jdbc.queryForObject("""
                insert into closing_recommendation_run(recommendation_date,generated_at,strategy_version,response_snapshot,execution_mode)
                values ('2026-09-16',now(),'test','{}','REPLAY') returning id
                """, Long.class);
        jdbc.update("""
                insert into closing_recommendation(recommendation_date,generated_at,stock_id,rank_no,
                  recommendation_score,buy_reference_price,recommendation_reason,risk_reason,strategy_version,status,candidate_observed_at,run_id)
                values ('2026-09-16',now(),?,1,80,100,'{}','{}','test','CANDIDATE',now(),?)
                """, stock, newRun);
        assertThat(jdbc.queryForObject("select count(*) from closing_recommendation", Integer.class)).isEqualTo(2);

        // 확인: 같은 요청 키의 동시 재시도가 advisory lock(트랜잭션 동안 잡는 PostgreSQL 이름 잠금)으로 줄 서는지 봐요.
        // ⚠️ 확인 필요: 이 재시도 로직은 ClosingRecommendationService의 잠금 SQL을 테스트 안에 복사한 것이라,
        // 운영 코드가 바뀌어도 이 테스트는 알아채지 못하는 것으로 보여요.
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var start = new CountDownLatch(1);
        Callable<Long> retry = () -> {
            start.await();
            return tx.execute(status -> {
                jdbc.queryForObject("select 1 from pg_advisory_xact_lock(hashtextextended(?, 0))", Integer.class, "concurrent-key");
                var ids = jdbc.queryForList("select id from closing_recommendation_run where request_key='concurrent-key'", Long.class);
                if (!ids.isEmpty()) return ids.getFirst();
                return jdbc.queryForObject("""
                        insert into closing_recommendation_run(recommendation_date,generated_at,strategy_version,response_snapshot,request_key)
                        values ('2026-09-16',now(),'test','{}','concurrent-key') returning id
                        """, Long.class);
            });
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(retry);
            var second = executor.submit(retry);
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from closing_recommendation_run where request_key='concurrent-key'", Integer.class)).isEqualTo(1);

        // 확인: 트랜잭션 중 예외가 나면 넣던 run이 롤백돼 남지 않아요.
        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.update("insert into closing_recommendation_run(recommendation_date,generated_at,strategy_version,response_snapshot,request_key) values ('2026-09-16',now(),'test','{}','rollback-key')");
            throw new IllegalStateException("serialization failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from closing_recommendation_run where request_key='rollback-key'", Integer.class)).isZero();
    }
}
