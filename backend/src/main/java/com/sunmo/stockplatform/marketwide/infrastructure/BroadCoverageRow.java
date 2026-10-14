/*
 * 커버리지 집계에 필요한 칸만 골라 담는 가벼운 조회 결과 모양(프로젝션, 표에서 필요한 열만 뽑아 보는 방식)이에요.
 * 종목별 최신 BROAD 스냅샷의 품질·수집 상태와 종목의 활성·관리·거래정지·ETF·ETN 여부만 읽어요.
 * MarketBroadSnapshotRepository.findLatestCoverage가 돌려주고 MarketCoverageService가 사용해요.
 */
package com.sunmo.stockplatform.marketwide.infrastructure;

import com.sunmo.stockplatform.marketwide.domain.*;

/**
 * 커버리지 계산에는 시세, 순위 원문(JSON), 출처 관측값이 필요 없어서 필요한 칸만 읽어 오는 조회 결과예요.
 * (원문: Coverage never needs quote, ranking JSON or source observations.)
 * 출석 체크할 때 학생 신상 기록 전체 대신 "이름과 출석 여부" 칸만 보는 것과 같아요.
 * 각 get 메서드는 같은 이름의 조회 열 값을 돌려줘요(받는 것 없음).
 */
public interface BroadCoverageRow {
    Long getStockId();
    BroadSnapshotQuality getDataQuality();
    BroadSnapshotStatus getCollectionStatus();
    boolean getActive();
    boolean getManaged();
    boolean getTradingHalted();
    boolean getEtf();
    boolean getEtn();
}
