/*
 * KIS 순위(랭킹, 거래량·등락률 등 순서대로 매긴 목록) API에서 뽑힌 BROAD 후보 종목 하나를 담아요.
 * 종목, 순위 기반 점수, 순위표별 등수, 체결강도, 순위표에서 받은 원본 관측값을 함께 들고 다녀요.
 * BroadCandidateCollector가 만들고 BroadSnapshotService·MarketWideScannerService가 사용해요.
 */
package com.sunmo.stockplatform.marketwide.domain;

import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.stock.domain.Stock;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.time.Instant;
import com.sunmo.stockplatform.kis.ranking.KisRankingEntry;

/**
 * 여러 순위표에 이름을 올린 BROAD 후보 종목 한 개의 정보 묶음이에요.
 * 여러 신문의 "인기 순위"에서 같은 가게가 몇 등씩 했는지 모아 적은 메모 카드라고 생각하면 돼요.
 */
public record BroadCandidate(Stock stock, BigDecimal rankingScore, Map<RankingType, Integer> ranks,
        BigDecimal tradeStrength, List<RankingObservation> observations) {
    /**
     * 하는 일: 원본 순위 관측값 없이 후보를 만들어요(관측 목록은 빈 목록으로 채워요).
     * 받는 것: 종목, 순위 점수, 순위표별 등수, 체결강도.
     * 돌려주는 것: 새 BroadCandidate(생성자라 따로 반환값은 없어요).
     */
    public BroadCandidate(Stock stock, BigDecimal score, Map<RankingType, Integer> ranks, BigDecimal strength) {
        this(stock, score, ranks, strength, List.of());
    }
    /**
     * 어떤 순위표(type)에서 어떤 행(entry)을 언제(receivedAt, 우리 서버가 받은 시각) 받았는지 남기는 기록이에요.
     * 신문 기사를 오려 붙이면서 "몇 일 몇 시에 받은 신문"인지 적어 두는 스크랩과 같아요.
     */
    public record RankingObservation(RankingType type, KisRankingEntry entry, Instant receivedAt) { }
}
