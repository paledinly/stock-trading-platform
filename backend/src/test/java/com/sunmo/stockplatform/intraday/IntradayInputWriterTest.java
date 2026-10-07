package com.sunmo.stockplatform.intraday;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IntradayInputWriterTest {
    @Test void multiRowInsertPreservesOrderAndWholeTransactionRollsBackOnLaterChunkFailure() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:inputwriter;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V27__create_intraday_research.sql")).execute(ds);
        var jdbc=spy(new JdbcTemplate(ds)); var mapper=new ObjectMapper().findAndRegisterModules();
        var writer=new IntradayInputWriter(jdbc,mapper);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var base=IntradayEngineTest.breakoutInputs().getFirst();
        var rows=new ArrayList<IntradayModel.Input>();
        for(int i=0;i<501;i++) rows.add(new IntradayModel.Input(base.observation(),base.evaluatedAt(),"row"+i,base.eligible(),base.dataLoss(),base.policy()));
        var tick=base.observation().tick();
        var invalidTick=new MarketTick("TOO_LONG_STOCK_CODE",tick.businessDate(),tick.occurredAt(),tick.price(),tick.tradeVolume(),tick.cumulativeVolume(),tick.cumulativeTradingValue(),tick.sequence());
        var bad=new IntradayModel.Input(new ObservedMarketTick(invalidTick,null,base.observation().receivedAt()),base.evaluatedAt(),"bad",true,false,base.policy());
        var failed=new ArrayList<>(rows.subList(0,250)); failed.add(bad);
        assertThatThrownBy(()->tx.executeWithoutResult(s->writer.save(failed))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM intraday_input",Integer.class)).isZero();
        clearInvocations(jdbc);
        tx.executeWithoutResult(s->writer.save(rows));
        verify(jdbc,times(3)).update(anyString(),any(Object[].class));
        var stored=jdbc.queryForList("SELECT payload FROM intraday_input ORDER BY id",String.class);
        assertThat(stored).hasSize(501);
        for(int i=0;i<stored.size();i++) {
            try { assertThat(mapper.readValue(stored.get(i),IntradayModel.Input.class)).isEqualTo(rows.get(i)); }
            catch(Exception e) { throw new AssertionError(e); }
        }
    }
}
