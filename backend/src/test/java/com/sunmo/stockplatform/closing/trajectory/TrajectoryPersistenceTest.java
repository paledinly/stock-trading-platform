/*
 * TrajectoryStore 가 V28 마이그레이션 테이블에 분봉·스냅샷·맥락·결과를 저장하고 읽는 동작을 확인해요.
 * 한 번 저장한 값을 늦은 값으로 덮어쓰지 않고, 평가시각 이후에야 스냅샷을 돌려주는지가 point-in-time(그 시점에 알 수 있던 정보만 쓰기) 규칙의 핵심이에요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryFeaturesTest.*;

/** 궤적 저장소가 기록을 바꾸지 않고 시각 기준으로만 꺼내 주는지 확인하는 테스트예요. */
class TrajectoryPersistenceTest {
    /**
     * 무엇: 분봉·스냅샷·맥락은 처음 저장한 값만 남고, 스냅샷은 평가시각(evaluatedAt) 이후 조회에서만 보이는지 확인해요.
     * 입력: H2(테스트용 메모리 DB)에 V28 스키마를 만들고, 같은 분봉/스냅샷/맥락을 두 번씩 저장해요.
     * 기대: 두 번째 저장은 무시되고, 스냅샷은 timestamp 시각 조회에선 null, evaluatedAt 조회에선 반환돼요.
     */
    @Test void migrationPersistsImmutableMinutesAndSnapshotsWithAvailabilityCutoff() {
        // 준비: H2 에 실제 마이그레이션 SQL 을 적용해요.
        var ds = new DriverManagerDataSource("jdbc:h2:mem:trajectory;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V28__create_closing_trajectory.sql")).execute(ds);
        var store = new TrajectoryStore(new JdbcTemplate(ds), new ObjectMapper().findAndRegisterModules());
        // 확인: 같은 분(0분)에 다른 값을 다시 저장해도 처음 값이 유지돼야 해요(늦은 값으로 덮어쓰기 금지).
        var minute = row(0, "105", "100", "120"); store.minute(minute); store.minute(row(0, "999", "999", "999"));
        assertThat(store.minutes("005930", START, START.plusSeconds(60))).singleElement().isEqualTo(minute);
        var s = TrajectoryFeatures.calculate("005930", "KOSPI", START.plusSeconds(60), START.plusSeconds(62), List.of(minute), List.of(), List.of(), policy());
        // 스냅샷은 첫 저장만 true, 중복은 false 예요. 조회 기준 시각이 evaluatedAt 보다 이르면 아직 사용할 수 없어요.
        assertThat(store.snapshot(s)).isTrue(); assertThat(store.snapshot(s)).isFalse();
        assertThat(store.available("005930", s.timestamp(), s.timestamp())).isNull();
        assertThat(store.available("005930", s.timestamp(), s.evaluatedAt()).timestamp()).isEqualTo(s.timestamp());
        store.outcome(s, Map.of("status", "PENDING"));
        // 맥락(외국인 순매수 추정치, 원천 시각 미상)도 중복 저장 시 한 건만 남아야 해요.
        var flow = new TrajectoryModel.Context("FOREIGN_NET_BUY", "005930", "KIS_ESTIMATE_ALL", START,
                java.math.BigDecimal.TEN, null, null, "ESTIMATED_SOURCE_TIME_UNKNOWN", "SHARES");
        store.context(flow); store.context(flow);
        assertThat(store.contexts("005930", "KOSPI", START, START.plusSeconds(60))).containsExactly(flow);
        assertThat(store.recentSymbols(START, START.plusSeconds(60), 20)).containsExactly("005930");
        assertThat(store.outcomes(START, START.plusSeconds(3600))).hasSize(1);
    }
}
