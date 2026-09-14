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

@Service
public class BroadSnapshotService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final long BUCKET_SECONDS = 120;
    private final MarketBroadSnapshotRepository snapshots;
    private final ObjectMapper objectMapper;

    public BroadSnapshotService(MarketBroadSnapshotRepository snapshots, ObjectMapper objectMapper) {
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, MarketBroadSnapshot> save(Instant capturedAt, List<Capture> captures) {
        Instant bucket = bucket(capturedAt);
        LocalDate sessionDate = bucket.atZone(MARKET_ZONE).toLocalDate();
        List<MarketBroadSnapshot> saved = new ArrayList<>();
        for (Capture capture : captures) {
            BroadCandidate candidate = capture.candidate();
            MarketBroadSnapshot snapshot = snapshots
                    .findBySessionDateAndCapturedAtAndStockId(sessionDate, bucket, candidate.stock().getId())
                    .orElseGet(() -> new MarketBroadSnapshot(sessionDate, bucket, candidate.stock()));
            snapshot.updateData(capture.data(), capture.broadScore(), rankingSources(candidate),
                    candidate.tradeStrength(), capture.error());
            saved.add(snapshot);
        }
        return snapshots.saveAll(saved).stream().collect(Collectors.toMap(
                snapshot -> snapshot.getStock().getStockCode(), snapshot -> snapshot));
    }

    @Transactional
    public MarketBroadSnapshot saveEnriched(Instant completedAt, BroadCandidate candidate, BroadQuoteData data) {
        if (data == null || !data.complete() || data.observedAt().isAfter(completedAt)
                || !data.observedAt().atZone(MARKET_ZONE).toLocalDate().equals(completedAt.atZone(MARKET_ZONE).toLocalDate()))
            throw new IllegalArgumentException("Invalid enrichment observation time");
        // Separate observation: delayed price must not replace an earlier scan snapshot.
        var snapshot = new MarketBroadSnapshot(completedAt.atZone(MARKET_ZONE).toLocalDate(), completedAt, candidate.stock());
        var enriched = new BroadQuoteData(data.price(), data.changeRate(), data.volume(), data.tradingValue(),
                data.open(), data.high(), data.low(), data.tradeStrength(), data.observedAt(), "ENRICHED_" + data.source());
        snapshot.updateData(enriched, MarketWideScannerService.combinedScore(data, candidate), rankingSources(candidate),
                candidate.tradeStrength(), null);
        return snapshots.save(snapshot);
    }

    Instant bucket(Instant instant) {
        long seconds = Math.floorDiv(instant.getEpochSecond(), BUCKET_SECONDS) * BUCKET_SECONDS;
        return Instant.ofEpochSecond(seconds);
    }

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

    public record Capture(BroadCandidate candidate, StockQuote quote, BigDecimal broadScore, String error,
            BroadQuoteData data) {
        public Capture(BroadCandidate candidate, StockQuote quote, BigDecimal score, String error) {
            this(candidate, quote, score, error, quote == null ? null : BroadQuoteData.fromQuote(quote, "REST"));
        }
    }
}
