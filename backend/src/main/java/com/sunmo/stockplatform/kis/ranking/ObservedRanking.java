/*
 * KIS 순위를 받았다는 사실을 다른 부품에 알리는 이벤트 데이터예요.
 * 어떤 순위를, 어느 시장에서, 언제 받았는지와 받은 항목들을 함께 담아요.
 * KisMarketRankingClient가 보내고, 후보 Context 수집기(TrajectoryContextCollector)가 받아 기록해요.
 */
package com.sunmo.stockplatform.kis.ranking;

import java.time.Instant;
import java.util.List;
import com.sunmo.stockplatform.stock.domain.Market;

/**
 * "방금 이 순위표를 받았어요" 하고 돌리는 회람 쪽지 같은 이벤트(다른 부품에 보내는 알림)예요.
 * receivedAt은 KIS 원천 시각이 아니라 우리 서버가 응답을 받은 시각이에요.
 */
public record ObservedRanking(RankingType type, Market market, Instant receivedAt, List<KisRankingEntry> entries) {}
