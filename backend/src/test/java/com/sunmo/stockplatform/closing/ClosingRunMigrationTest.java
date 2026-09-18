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

@Testcontainers(disabledWithoutDocker = true)
class ClosingRunMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void upgradePreservesReferencesAndConcurrentRetriesSerialize() throws Exception {
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

        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.update("insert into closing_recommendation_run(recommendation_date,generated_at,strategy_version,response_snapshot,request_key) values ('2026-09-16',now(),'test','{}','rollback-key')");
            throw new IllegalStateException("serialization failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from closing_recommendation_run where request_key='rollback-key'", Integer.class)).isZero();
    }
}
