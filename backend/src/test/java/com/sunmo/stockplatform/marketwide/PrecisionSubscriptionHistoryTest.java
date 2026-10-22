/*
 * PrecisionSubscriptionHistory(정밀 구독 세션 기록) 테스트예요. 이전 프로세스가 열어 둔 세션을 시작 때 닫고,
 * 날짜가 바뀌어 이어진 구독은 전날 세션을 닫고 오늘 활성 세션을 새로 만드는지 확인해요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.marketwide.domain.PrecisionSubscriptionSession;
import com.sunmo.stockplatform.marketwide.domain.PrecisionSubscriptionSession.Status;
import com.sunmo.stockplatform.marketwide.infrastructure.PrecisionSubscriptionSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class PrecisionSubscriptionHistoryTest {
    @Test
    void closesSessionsLeftOpenByThePreviousProcess() {
        var repository = mock(PrecisionSubscriptionSessionRepository.class);
        var active = new PrecisionSubscriptionSession("A00001", Instant.parse("2026-10-21T00:00:00Z"), true);
        var requested = new PrecisionSubscriptionSession("A00002", Instant.parse("2026-10-21T00:00:00Z"), false);
        when(repository.findByStatusInAndRequestedAtBefore(anyCollection(), any())).thenReturn(List.of(active, requested));

        new PrecisionSubscriptionHistory(repository).closeLeftovers();

        assertThat(List.of(active, requested)).allSatisfy(session -> {
            assertThat(session.getStatus()).isEqualTo(Status.ENDED);
            assertThat(session.getEndReason()).isEqualTo("PROCESS_RESTART");
        });
    }

    @Test
    void rollsACarriedSubscriptionIntoTodaysSession() {
        var repository = mock(PrecisionSubscriptionSessionRepository.class);
        var yesterday = new PrecisionSubscriptionSession("A00001", Instant.parse("2026-10-19T00:00:00Z"), true);
        when(repository.findFirstByStockCodeAndStatusOrderByRequestedAtDesc("A00001", Status.ACTIVE))
                .thenReturn(Optional.of(yesterday));
        Instant morning = Instant.parse("2026-10-20T00:00:30Z");

        new PrecisionSubscriptionHistory(repository).rolledOver("A00001", morning);

        assertThat(yesterday.getStatus()).isEqualTo(Status.ENDED);
        assertThat(yesterday.getEndReason()).isEqualTo("DAY_ROLLOVER");
        ArgumentCaptor<PrecisionSubscriptionSession> saved = ArgumentCaptor.forClass(PrecisionSubscriptionSession.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSessionDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(saved.getValue().getStatus()).isEqualTo(Status.ACTIVE);
    }
}
