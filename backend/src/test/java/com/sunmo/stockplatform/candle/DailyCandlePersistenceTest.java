/*
 * DailyCandlePersistence가 KIS에서 받은 일봉 중 아직 없는 것만 저장하는지 확인해요.
 * 추천 기준일 이후의 일봉이 섞이면 미래 정보가 평가에 새어 들어가므로 중요해요.
 */
package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.DailyCandlePersistence;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.kis.candle.DailyCandle;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 일봉 저장 규칙을 점검하는 테스트예요. */
class DailyCandlePersistenceTest {
    /**
     * 추천일 이전의 누락된 확정 원본 일봉만 저장하는지 확인해요.
     * 준비: 저장소는 목(mock, 가짜 객체)으로 비어 있게 두고, 같은 날 일봉 2개와 추천일 이후 일봉 1개를 넘겨요.
     * 기대: 1건만 저장되고, 저장된 봉은 1D·확정·서울 자정 시작·BACKFILL 출처예요.
     */
    @Test
    void savesOnlyMissingFinalRawDailyCandlesBeforeRecommendationDate() {
        // 준비
        StockCandleRepository repository = mock(StockCandleRepository.class);
        DailyCandlePersistence service = new DailyCandlePersistence(repository);
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(repository.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("1D"), any(), any())).thenReturn(List.of());
        LocalDate prior = LocalDate.of(2026, 9, 16);
        DailyCandle valid = candle(prior);

        // 실행
        int saved = service.saveMissing(stock, prior.minusDays(1), prior,
                List.of(valid, valid, candle(prior.plusDays(1))));

        // 확인
        assertThat(saved).isEqualTo(1);
        @SuppressWarnings("unchecked")
        var rows = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        StockCandle stored = (StockCandle) rows.getValue().getFirst();
        assertThat(stored.getTimeframe()).isEqualTo("1D");
        assertThat(stored.isFinalCandle()).isTrue();
        assertThat(stored.getStartTime()).isEqualTo(prior.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant());
        assertThat(stored.getSource().name()).isEqualTo("BACKFILL");
    }

    /** 지정한 날짜의 테스트용 일봉을 만드는 도우미예요. */
    private DailyCandle candle(LocalDate date) {
        return new DailyCandle(date, BigDecimal.valueOf(100), BigDecimal.valueOf(105),
                BigDecimal.valueOf(99), BigDecimal.valueOf(102), 10, BigDecimal.valueOf(1000));
    }
}
