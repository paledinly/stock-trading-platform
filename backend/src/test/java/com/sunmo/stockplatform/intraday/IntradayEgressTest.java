package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.application.MarketEventGateway;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.*;
import java.util.*;
import static com.sunmo.stockplatform.intraday.IntradayEngineTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntradayEgressTest {
    private final ClosingTradingCalendar clock = mock(ClosingTradingCalendar.class);
    private final IntradayRecommendationRepository rows = mock(IntradayRecommendationRepository.class);
    private final IntradayInputRepository inputs = mock(IntradayInputRepository.class);
    private final IntradayInputWriter writer = mock(IntradayInputWriter.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final IntradayService service = new IntradayService(policy(), inputs, rows, mock(StockRepository.class),
            clock, JSON, mock(MarketEventGateway.class), tx, writer, mock(IntradayCheckpointStore.class));

    @SuppressWarnings("unchecked") private Map<UUID, String> committed() {
        return (Map<UUID, String>) ReflectionTestUtils.getField(service, "committedOutcomes");
    }
    private IntradayModel.Result seed() throws Exception {
        var r = signal(); var engine = new IntradayEngine(clock); engine.restore(r);
        LocalDate day = r.signal().recommendedAt().atZone(IntradayEngine.ZONE).toLocalDate();
        when(clock.today()).thenReturn(day);
        when(clock.close(day)).thenReturn(day.atTime(15, 30).atZone(IntradayEngine.ZONE).toInstant());
        when(clock.now()).thenReturn(r.signal().recommendedAt().plusSeconds(1));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "engine", engine);
        ReflectionTestUtils.setField(service, "date", day);
        committed().put(r.signal().id(), JSON.writeValueAsString(r.outcome()));
        return r;
    }
    @Test void unchangedTicksFreeBatchesNeverReadRecommendationsOrOpenTransactions() throws Exception {
        seed();
        for (int i = 0; i < 100; i++) service.flush();
        verifyNoInteractions(rows, inputs, writer, tx);
    }
    @Test void expiryWithoutTicksPersistsOnceThenBecomesDatabaseIdle() throws Exception {
        var r = seed();
        when(clock.now()).thenReturn(r.signal().expiresAt());
        var entity = new IntradayRecommendationEntity(r.signal(), JSON.writeValueAsString(r.signal()), JSON.writeValueAsString(r.outcome()));
        when(rows.findAllById(any())).thenReturn(List.of(entity));
        service.flush();
        assertThat(JSON.readValue(entity.getOutcome(), IntradayModel.Outcome.class).status).isEqualTo("EXPIRED");
        verify(tx).commit(any());
        clearInvocations(rows, inputs, writer, tx);
        service.flush();
        verifyNoInteractions(rows, inputs, writer, tx);
    }
    @Test void onlyChangedIdsAreFetchedAndImmutableSnapshotSurvives() throws Exception {
        var r = seed(); var engine = (IntradayEngine) ReflectionTestUtils.getField(service, "engine");
        var s = r.signal(); var otherSignal = new IntradayModel.Signal(UUID.randomUUID(), "000660", "other", s.setup(), s.session(),
                s.sourceAt(), s.receivedAt(), s.recommendedAt(), s.expiresAt(), s.price(), s.plan(), s.score(), s.features(), s.reasons(), s.risks(), s.policy());
        var other = new IntradayModel.Result(otherSignal, new IntradayModel.Outcome()); engine.restore(other);
        committed().put(otherSignal.id(), JSON.writeValueAsString(other.outcome()));
        String frozen = JSON.writeValueAsString(s);
        var entity = new IntradayRecommendationEntity(s, frozen, JSON.writeValueAsString(r.outcome()));
        r.outcome().quality("TEST_CHANGE");
        when(rows.findAllById(any())).thenAnswer(invocation -> {
            assertThat((Iterable<UUID>) invocation.getArgument(0)).containsExactly(s.id());
            return List.of(entity);
        });
        service.flush();
        verify(rows).findAllById(any());
        assertThat(entity.getSnapshot()).isEqualTo(frozen);
        assertThat(service.status().get("error")).isNull();
    }
    @Test void failedCommitDiscardsComparisonStateAndEngine() throws Exception {
        var r = seed(); r.outcome().quality("UNCOMMITTED");
        when(rows.findAllById(any())).thenReturn(List.of());
        doThrow(new TransactionSystemException("simulated commit failure")).when(tx).commit(any());
        service.flush();
        assertThat(committed()).isEmpty();
        assertThat(ReflectionTestUtils.getField(service, "engine")).isNull();
        assertThat(service.status().get("error")).isEqualTo("INTRADAY_PROCESSING_FAILED");
    }
    @Test void afterHoursRestartRestoresCompletedPaperWithoutDownloadingTickLedger() throws Exception {
        var r = seed(); r.outcome().status = "STOPPED"; r.outcome().trackingComplete = true;
        var entity = new IntradayRecommendationEntity(r.signal(), JSON.writeValueAsString(r.signal()), JSON.writeValueAsString(r.outcome()));
        LocalDate day = clock.today();
        Instant afterClose = clock.close(day).plusSeconds(3600);
        when(clock.now()).thenReturn(afterClose);
        when(rows.findByTrackingCompleteFalse()).thenReturn(List.of());
        when(rows.findBySessionDateOrderByRecommendedAtAsc(day)).thenReturn(List.of(entity));
        ReflectionTestUtils.setField(service, "engine", null);
        service.flush();
        verifyNoInteractions(inputs, writer);
        verify(rows, never()).findAllById(any());
        assertThat(service.status().get("error")).isNull();
        var engine = (IntradayEngine) ReflectionTestUtils.getField(service, "engine");
        assertThat(engine.results().iterator().next().outcome().status).isEqualTo("STOPPED");
        clearInvocations(rows, tx); service.flush(); verifyNoInteractions(rows, tx);
    }
}
