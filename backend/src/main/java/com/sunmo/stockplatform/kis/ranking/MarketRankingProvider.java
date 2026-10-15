/*
 * 시장 순위를 가져오는 기능의 약속(인터페이스)만 정해 둔 파일이에요.
 * 실제 구현은 KisMarketRankingClient이고, 테스트에서는 가짜 구현으로 바꿔 끼울 수 있어요.
 * 시장 전체 스캔(BroadCandidateCollector)과 상한가 감시(LimitUpWatchService)가 이 이름으로 순위를 요청해요.
 */
package com.sunmo.stockplatform.kis.ranking;

import com.sunmo.stockplatform.stock.domain.Market;
import java.util.List;

/**
 * 순위표를 가져다주는 창구의 규격이에요.
 * 콘센트 모양만 맞으면 어떤 플러그든 꽂을 수 있듯, 이 규격만 지키면 실제 KIS 구현이든 테스트용 가짜든 바꿔 쓸 수 있어요.
 */
public interface MarketRankingProvider {
    /**
     * 순위 목록을 가져와요.
     * 받는 것: 순위 종류, 시장(null이면 전체), 최대 개수. 돌려주는 것: 순위 순서의 KisRankingEntry 목록.
     */
    List<KisRankingEntry> fetch(RankingType type, Market market, int limit);

    /**
     * 오늘 전일 대비 상승률 상위 종목을 가져와요(상한가 감시 전용). 기본 구현은 PRICE_RISE 순위를 그대로 써요.
     * KIS 구현은 등락률 순위의 누적 일수 0·가격 구분 1 조건으로 오늘 상승률 순서를 받아요. 시장 스캔의 PRICE_RISE는 바꾸지 않아요.
     * 받는 것: 시장, 최대 개수. 돌려주는 것: 상승률 내림차순 목록.
     */
    default List<KisRankingEntry> fetchDailyGainers(Market market, int limit) {
        return fetch(RankingType.PRICE_RISE, market, limit);
    }
}
