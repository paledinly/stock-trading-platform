/*
 * 시장 스캔 회차 기록(MarketWideScanRun)을 DB에서 읽고 쓰는 레포지토리(DB 창고 담당자)예요.
 * 예정 시각으로 한 회차를 찾거나, 거래일의 모든 회차를 시간순으로 가져와요.
 * MarketWideScanCoordinator와 MarketCoverageService가 사용해요.
 */
package com.sunmo.stockplatform.marketwide.infrastructure;

import com.sunmo.stockplatform.marketwide.domain.MarketWideScanRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Optional;
import java.time.LocalDate;
import java.util.List;

/**
 * 스캔 회차 기록을 보관하고 꺼내 주는 창구예요.
 * 운행 일지 보관함에서 "몇 시 차 기록"이나 "오늘 운행 전체"를 찾아 주는 직원과 같아요.
 */
public interface MarketWideScanRunRepository extends JpaRepository<MarketWideScanRun, Long> {
    /**
     * 하는 일: 예정 시각이 같은 회차 기록을 찾아요(예정 시각당 한 줄만 있어요).
     * 받는 것: 예정 시각. 돌려주는 것: 찾은 기록(없으면 빈 Optional).
     */
    Optional<MarketWideScanRun> findByScheduledFor(Instant scheduledFor);
    /**
     * 하는 일: 거래일의 모든 회차 기록을 예정 시각 오름차순으로 가져와요.
     * 받는 것: 거래일. 돌려주는 것: 회차 기록 목록.
     */
    List<MarketWideScanRun> findBySessionDateOrderByScheduledForAsc(LocalDate sessionDate);
}
