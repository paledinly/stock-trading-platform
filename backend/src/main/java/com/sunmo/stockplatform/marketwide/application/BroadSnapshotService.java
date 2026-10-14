/*
 * 시장 전체 스캔에서 본 BROAD 후보들의 시세를 스냅샷(그 순간 찍어 둔 기록)으로 DB에 저장하는 파일이에요.
 * 스캔 결과는 2분 단위 칸(버킷)에 저장하고, 늦게 보강된 시세는 덮어쓰지 않고 별도 관측으로 남겨요.
 * MarketWideScannerService와 BroadEnrichmentQueue가 사용해요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.marketwide.domain.BroadCandidate;
import com.sunmo.stockplatform.marketwide.domain.BroadQuoteData;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketBroadSnapshotRepository;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BROAD 후보 시세 스냅샷을 만들고 저장하는 서비스예요.
 * 2분마다 같은 자리에서 사진을 찍어 앨범 칸에 꽂고, 나중에 받은 사진은 원래 사진 위에 붙이지 않고 새 칸에 따로 꽂는 사진사와 같아요.
 */
@Service
public class BroadSnapshotService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    // 스냅샷 시각을 2분(120초) 단위로 묶어요.
    private static final long BUCKET_SECONDS = 120;
    private final MarketBroadSnapshotRepository snapshots;
    private final ObjectMapper objectMapper;

    /**
     * 하는 일: 스냅샷 레포지토리(DB 창구)와 JSON 변환기를 받아 둬요.
     * 받는 것: 스냅샷 레포지토리, ObjectMapper.
     */
    public BroadSnapshotService(MarketBroadSnapshotRepository snapshots, ObjectMapper objectMapper) {
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
    }

    /**
     * 하는 일: 스캔에서 얻은 후보들의 시세를 2분 버킷 스냅샷으로 저장해요.
     * 같은 날·같은 버킷·같은 종목 행이 있으면 그 행을 갱신하고, 없으면 새로 만들어요.
     * 받는 것: 수집 시각, 후보별 수집 결과(Capture) 목록.
     * 돌려주는 것: 종목코드 → 저장된 스냅샷 맵.
     */
    @Transactional
    public Map<String, MarketBroadSnapshot> save(Instant capturedAt, List<Capture> captures) {
        // 준비: 수집 시각을 2분 버킷으로 내리고, 한국 날짜를 세션 날짜로 써요.
        Instant bucket = bucket(capturedAt);
        LocalDate sessionDate = bucket.atZone(MARKET_ZONE).toLocalDate();
        List<MarketBroadSnapshot> saved = new ArrayList<>();
        // 처리: 후보마다 기존 행을 찾거나 새로 만들어 시세·점수·랭킹 출처를 채워요.
        for (Capture capture : captures) {
            BroadCandidate candidate = capture.candidate();
            MarketBroadSnapshot snapshot = snapshots
                    .findBySessionDateAndCapturedAtAndStockId(sessionDate, bucket, candidate.stock().getId())
                    .orElseGet(() -> new MarketBroadSnapshot(sessionDate, bucket, candidate.stock()));
            snapshot.updateData(capture.data(), capture.broadScore(), rankingSources(candidate),
                    candidate.tradeStrength(), capture.error());
            saved.add(snapshot);
        }
        // 결과: 한 번에 저장하고 종목코드로 찾기 쉽게 맵으로 돌려줘요.
        // 같은 종목코드가 captures에 두 번 오면 toMap이 예외를 내므로, 호출 쪽에서 후보가 중복 없이 온다고 가정하는 것으로 보여요.
        return snapshots.saveAll(saved).stream().collect(Collectors.toMap(
                snapshot -> snapshot.getStock().getStockCode(), snapshot -> snapshot));
    }

    /**
     * 하는 일: 스캔 뒤에 늦게 보강한 시세를 새 스냅샷 행으로 저장해요.
     * 관측 시각이 완료 시각보다 미래이거나 다른 날짜면 잘못된 값으로 보고 거절해요.
     * 받는 것: 보강 완료 시각, 후보, 보강된 시세 데이터.
     * 돌려주는 것: 저장된 스냅샷.
     */
    @Transactional
    public MarketBroadSnapshot saveEnriched(Instant completedAt, BroadCandidate candidate, BroadQuoteData data) {
        if (data == null || !data.complete() || data.observedAt().isAfter(completedAt)
                || !data.observedAt().atZone(MARKET_ZONE).toLocalDate().equals(completedAt.atZone(MARKET_ZONE).toLocalDate()))
            throw new IllegalArgumentException("Invalid enrichment observation time");
        // Separate observation: delayed price must not replace an earlier scan snapshot.
        var snapshot = new MarketBroadSnapshot(completedAt.atZone(MARKET_ZONE).toLocalDate(), completedAt, candidate.stock());
        // 출처 앞에 ENRICHED_를 붙여 나중에 보강된 값임을 구분해요.
        var enriched = new BroadQuoteData(data.price(), data.changeRate(), data.volume(), data.tradingValue(),
                data.open(), data.high(), data.low(), data.tradeStrength(), data.observedAt(), "ENRICHED_" + data.source());
        snapshot.updateData(enriched, MarketWideScannerService.combinedScore(data, candidate), rankingSources(candidate),
                candidate.tradeStrength(), null);
        return snapshots.save(snapshot);
    }

    /**
     * 하는 일: 시각을 2분 단위로 내림해서 버킷 시각을 만들어요.
     * 받는 것: 원래 시각.
     * 돌려주는 것: 2분 경계로 내린 시각.
     */
    Instant bucket(Instant instant) {
        long seconds = Math.floorDiv(instant.getEpochSecond(), BUCKET_SECONDS) * BUCKET_SECONDS;
        return Instant.ofEpochSecond(seconds);
    }

    /**
     * 하는 일: 후보가 어떤 랭킹(순위표)에서 몇 위였는지, 랭킹 점수, 원본 관측값을 JSON 문자열로 만들어요.
     * 키 순서를 고정(TreeMap)해서 같은 내용이면 같은 문자열이 나오게 해요.
     * 받는 것: BROAD 후보.
     * 돌려주는 것: 랭킹 출처 JSON 문자열.
     */
    private String rankingSources(BroadCandidate candidate) {
        Map<String, Object> value = new TreeMap<>();
        value.put("sourceVersion", MarketBroadSnapshot.SOURCE_VERSION);
        Map<String, Integer> ranks = candidate.ranks().entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().name(), Map.Entry::getValue, (left, right) -> left, TreeMap::new));
        value.put("ranks", ranks);
        value.put("rankingScore", candidate.rankingScore().toPlainString());
        value.put("observations", candidate.observations());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize broad ranking sources", error);
        }
    }

    /**
     * 후보 한 종목의 수집 결과를 담는 기록이에요.
     * candidate는 후보, quote는 REST 시세(없을 수 있음), broadScore는 BROAD 점수, error는 오류, data는 저장할 시세예요.
     */
    public record Capture(BroadCandidate candidate, StockQuote quote, BigDecimal broadScore, String error,
            BroadQuoteData data) {
        /**
         * 하는 일: REST 시세만 있을 때 쓰는 편의 생성자예요. quote가 있으면 출처 "REST"로 시세 데이터를 만들어 채워요.
         * 받는 것: 후보, REST 시세, 점수, 오류.
         */
        public Capture(BroadCandidate candidate, StockQuote quote, BigDecimal score, String error) {
            this(candidate, quote, score, error, quote == null ? null : BroadQuoteData.fromQuote(quote, "REST"));
        }
    }
}
