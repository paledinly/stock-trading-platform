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

class TrajectoryPersistenceTest {
    @Test void migrationPersistsImmutableMinutesAndSnapshotsWithAvailabilityCutoff() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:trajectory;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V28__create_closing_trajectory.sql")).execute(ds);
        var store = new TrajectoryStore(new JdbcTemplate(ds), new ObjectMapper().findAndRegisterModules());
        var minute = row(0, "105", "100", "120"); store.minute(minute); store.minute(row(0, "999", "999", "999"));
        assertThat(store.minutes("005930", START, START.plusSeconds(60))).singleElement().isEqualTo(minute);
        var s = TrajectoryFeatures.calculate("005930", "KOSPI", START.plusSeconds(60), START.plusSeconds(62), List.of(minute), List.of(), List.of(), policy());
        assertThat(store.snapshot(s)).isTrue(); assertThat(store.snapshot(s)).isFalse();
        assertThat(store.available("005930", s.timestamp(), s.timestamp())).isNull();
        assertThat(store.available("005930", s.timestamp(), s.evaluatedAt()).timestamp()).isEqualTo(s.timestamp());
        store.outcome(s, Map.of("status", "PENDING"));
        assertThat(store.outcomes(START, START.plusSeconds(3600))).hasSize(1);
    }
}
