/*
 * 시장 전체 스캔 한 번을 처음부터 끝까지 실행하는 파일이에요.
 * 랭킹으로 BROAD 후보를 모으고 → 시세를 채우고 → 점수를 매겨 스냅샷으로 저장한 뒤 → 상위 후보로 정밀 실시간 구독 자리를 조정해요.
 * MarketWideScanCoordinator가 수동/자동 스캔 때 scan()을 불러요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.BroadScanResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.CandidateResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.RegimeResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.RankingSourceResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.PrecisionAllocationResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.PrecisionAllocationItemResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.UniverseResponse;
import com.sunmo.stockplatform.marketwide.domain.BroadCandidate;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import com.sunmo.stockplatform.marketwide.domain.BroadQuoteData;
import com.sunmo.stockplatform.market.config.BroadCollectionProperties;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.CollectionSummary;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 시장 전체 스캔의 전체 흐름을 지휘하는 서비스예요.
 * 넓은 그물(랭킹)로 물고기를 잔뜩 건져 올린 뒤, 크기를 재서(점수) 기록하고, 가장 좋은 몇 마리만 수조(정밀 실시간 구독)에 옮겨 자세히 지켜보는 어부와 같아요.
 * 점수는 상승 확률이 아니라 후보를 고르는 순서 기준이에요.
 */
@Service
public class MarketWideScannerService {
    private final StockRepository stocks;
    private final BroadQuoteResolver quotes;
    private final BroadCollectionProperties properties;
    private final KisRequestExecutor requests;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final BroadCandidateCollector collector;
    private final BroadSnapshotService broadSnapshots;
    private final PrecisionSubscriptionAllocator precision;
    private final BroadEnrichmentQueue enrichment;

    /**
     * 하는 일: 스캔 한 번에 필요한 협력 객체들을 받아 둬요.
     * 받는 것: 종목 레포지토리(DB 창구), 시세 선택기, 실시간 구독 등록부, 후보 수집기, 스냅샷 서비스,
     * 정밀 구독 배정기, 수집 설정, KIS 요청 실행기(진단용), 시세 보강 대기열.
     */
    public MarketWideScannerService(StockRepository stocks, BroadQuoteResolver quotes,
            RealtimeSubscriptionRegistry subscriptions, BroadCandidateCollector collector,
            BroadSnapshotService broadSnapshots, PrecisionSubscriptionAllocator precision,
            BroadCollectionProperties properties, KisRequestExecutor requests, BroadEnrichmentQueue enrichment) {
        this.stocks = stocks;
        this.quotes = quotes;
        this.subscriptions = subscriptions;
        this.collector = collector;
        this.broadSnapshots = broadSnapshots;
        this.precision = precision;
        this.properties = properties;
        this.requests = requests;
        this.enrichment = enrichment;
    }

    /**
     * 하는 일: 시장 전체 스캔을 한 번 실행해요.
     * 받는 것: 시장(null이면 전체), 시세를 볼 후보 수(1~120으로 제한), 최종 후보 수(1~41로 제한), ETF 포함 여부.
     * 돌려주는 것: 랭킹 출처 상태, 정밀 배정 결과, 종목 수, 시장 분위기, 최종 후보, 수집 요약을 담은 응답.
     */
    public BroadScanResponse scan(Market market, int limit, int candidates, boolean includeEtf) {
        int safeLimit = Math.min(Math.max(limit, 1), 120);
        // Up to the KIS realtime limit so precision can fill every free slot (ADR-010).
        int safeCandidates = Math.min(Math.max(candidates, 1), 41);
        // 준비: 랭킹으로 후보를 모으고, 상위 safeLimit개만 종목코드 기준으로 중복 없이 남겨요.
        BroadCandidateCollector.Result collected = collector.collect(market, safeLimit, includeEtf);
        Instant scannedAt = Instant.now();
        Map<String, BroadCandidate> byCode = collected.candidates().stream()
                .limit(safeLimit)
                .collect(Collectors.toMap(item -> item.stock().getStockCode(), item -> item,
                        (left, right) -> left, LinkedHashMap::new));
        // 처리 1: 후보마다 시세를 채워요. REST 조회는 detailQuoteBudget 횟수까지만 허락해요.
        // 시세가 완전하면 시세+랭킹 합산 점수, 아니면 랭킹 점수만 써요.
        List<QuoteAttempt> attempts = new ArrayList<>();
        int restLookups = 0;
        int restFailures = 0;
        for (BroadCandidate broad : byCode.values()) {
            var resolved = quotes.resolve(broad, restLookups < properties.detailQuoteBudget());
            if (resolved.restAttempted()) {
                restLookups++;
                if (resolved.error() != null) restFailures++;
            }
            BigDecimal score = resolved.data() != null && resolved.data().complete()
                    ? combinedScore(resolved.data(), broad) : rankingScore(broad);
            attempts.add(new QuoteAttempt(broad, resolved.data(), score, resolved.error()));
        }
        // 처리 2: 모든 시도를 스냅샷으로 저장하고, 시세가 불완전한 후보는 보강 대기열에 넘겨요.
        List<BroadSnapshotService.Capture> captures = attempts.stream()
                .map(attempt -> new BroadSnapshotService.Capture(attempt.broad(), null,
                        attempt.score(), attempt.error(), attempt.quote()))
                .toList();
        Map<String, MarketBroadSnapshot> persisted = broadSnapshots.save(scannedAt, captures);
        enrichment.enqueue(captures);
        // 처리 3: 시세가 완전한 후보만 점수순으로 최종 후보(최대 safeCandidates개)에 올려요.
        List<BroadQuoteData> snapshots = attempts.stream().map(QuoteAttempt::quote)
                .filter(data -> data != null && data.complete())
                .toList();
        List<CandidateResponse> shortlisted = attempts.stream()
                .filter(attempt -> attempt.quote() != null && attempt.quote().complete())
                .map(attempt -> candidate(attempt, persisted.get(attempt.broad().stock().getStockCode())))
                .sorted(Comparator.comparing(CandidateResponse::broadScore).reversed())
                .limit(safeCandidates)
                .toList();
        // 처리 4: 최종 후보로 정밀 실시간 구독 자리를 다시 맞춰요.
        PrecisionSubscriptionAllocator.Snapshot allocation = precision.reconcile(shortlisted.stream()
                .map(candidate -> new PrecisionSubscriptionAllocator.Candidate(candidate.stockCode(),
                        candidate.broadScore()))
                .toList());
        // 결과: 응답을 조립해요. 마지막 수집 요약의 "가장 오래된 시세 나이(초)"는 완전한 시세들의 관측 시각 기준이에요.
        return new BroadScanResponse(
                scannedAt,
                market == null ? "ALL" : market.name(),
                safeLimit,
                snapshots.size(),
                shortlisted.size(),
                collected.fallback(),
                collected.sources().stream()
                        .map(source -> new RankingSourceResponse(source.type().name(), source.success(),
                                source.candidateCount(), source.error()))
                        .toList(),
                allocation(allocation),
                new UniverseResponse(
                        stocks.countByActiveTrue(),
                        stocks.countByActiveTrueAndManagedFalseAndTradingHaltedFalse(),
                        subscriptions.limit(),
                        subscriptions.all().size(),
                        subscriptions.remaining()),
                regime(snapshots),
                shortlisted,
                new CollectionSummary(properties.detailQuoteBudget(), restLookups, restFailures,
                        attempts.size() - snapshots.size(), sourceCounts(attempts), snapshots.stream()
                            .mapToLong(row -> Math.max(0, java.time.Duration.between(row.observedAt(), Instant.now()).toSeconds()))
                            .max().orElse(0), requests.diagnostics()));
    }

    /**
     * 하는 일: 정밀 구독 배정 스냅샷을 API 응답 형태로 바꿔요.
     * 받는 것: 배정 스냅샷.
     * 돌려주는 것: 정밀 배정 응답.
     */
    private PrecisionAllocationResponse allocation(PrecisionSubscriptionAllocator.Snapshot snapshot) {
        return new PrecisionAllocationResponse(snapshot.state(), snapshot.evaluatedAt(), snapshot.capacity(),
                snapshot.activeCount(), snapshot.remainingSlots(), snapshot.reservedSlots(),
                snapshot.allocations().stream().map(item -> new PrecisionAllocationItemResponse(item.stockCode(),
                        item.score(), item.addedAt(), item.lastSeenAt(), item.awaitingAcknowledgement())).toList());
    }

    /**
     * 하는 일: 시세 출처(REALTIME_CACHE, CACHE, REST 등)별로 몇 건이었는지 세요. 시세가 없으면 NONE이에요.
     * 받는 것: 시세 시도 목록.
     * 돌려주는 것: 출처 → 건수 맵.
     */
    private Map<String, Integer> sourceCounts(List<QuoteAttempt> attempts) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        attempts.forEach(row -> counts.merge(row.quote() == null ? "NONE" : row.quote().source(), 1, Integer::sum));
        return counts;
    }

    /**
     * 하는 일: 시세 시도 하나를 화면용 후보 응답으로 바꿔요.
     * 후보가 된 이유는 들어간 랭킹 이름을 +로 이은 것이고, 랭킹이 없으면(대체 목록) FALLBACK_SAMPLE이에요.
     * 받는 것: 시세 시도(시세가 완전한 것), 저장된 스냅샷(없을 수 있음).
     * 돌려주는 것: 후보 응답.
     */
    private CandidateResponse candidate(QuoteAttempt attempt, MarketBroadSnapshot snapshot) {
        BroadQuoteData quote = attempt.quote();
        BroadCandidate broad = attempt.broad();
        List<String> sources = broad.ranks().keySet().stream().map(Enum::name).sorted().toList();
        String reason = sources.isEmpty() ? "FALLBACK_SAMPLE" : String.join("+", sources);
        Map<String, Integer> ranks = broad.ranks().entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue));
        return new CandidateResponse(
                broad.stock().getStockCode(),
                broad.stock().getStockName(),
                broad.stock().getMarket().name(),
                quote.price(),
                quote.changeRate(),
                quote.volume(),
                quote.tradingValue(),
                attempt.score(),
                reason,
                sources,
                ranks,
                snapshot == null ? null : snapshot.getId(),
                snapshot == null ? "INSUFFICIENT" : snapshot.getDataQuality().name(),
                // 실시간 구독 자리가 남아 있고 아직 구독하지 않은 종목인지 표시해요.
                subscriptions.remaining() > 0 && !subscriptions.all().contains(broad.stock().getStockCode()),
                quote.observedAt(), quote.source());
    }

    /**
     * 하는 일: 시세 점수 65% + 랭킹 점수 35%를 합쳐 BROAD 점수(0~100)를 만들어요.
     * 받는 것: 완전한 시세, 후보.
     * 돌려주는 것: 합산 점수(소수 3자리).
     */
    static BigDecimal combinedScore(BroadQuoteData quote, BroadCandidate broad) {
        return score(quote).multiply(bd("0.65")).add(rankingScore(broad).multiply(bd("0.35")))
                .setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 랭킹 원점수를 0~100으로 맞춰요. 원점수 최대치가 100 × 가중치 합(17) = 1700이라 17로 나눠요.
     * 받는 것: 후보.
     * 돌려주는 것: 0~100 랭킹 점수.
     */
    private static BigDecimal rankingScore(BroadCandidate broad) {
        return broad.rankingScore().divide(bd("17"), 6, RoundingMode.HALF_UP).min(bd("100"));
    }

    /**
     * 하는 일: 예외의 가장 안쪽 원인 메시지를 꺼내요. 지금 이 파일 안에서는 쓰이지 않는 것으로 보여요.
     * 받는 것: 예외.
     * 돌려주는 것: 원인 메시지(없으면 예외 클래스 이름).
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /**
     * 하는 일: 시세만으로 0~100 점수를 매겨요.
     * 구성: 상승 모멘텀(등락률 × 12, 최대 45) + 유동성(거래대금 억 원 단위, 최대 35) + 당일 범위 내 위치(고가에 가까울수록, 최대 20).
     * 받는 것: 완전한 시세.
     * 돌려주는 것: 시세 점수(소수 3자리).
     */
    private static BigDecimal score(BroadQuoteData quote) {
        BigDecimal momentum = positive(quote.changeRate()).multiply(bd("12")).min(bd("45"));
        BigDecimal liquidity = quote.tradingValue()
                .divide(bd("100000000"), 6, RoundingMode.HALF_UP)
                .min(bd("35"));
        BigDecimal rangePosition = quote.high() == null || quote.low() == null || quote.high().compareTo(quote.low()) <= 0
                ? BigDecimal.ZERO
                : quote.price().subtract(quote.low())
                        .divide(quote.high().subtract(quote.low()), 6, RoundingMode.HALF_UP)
                        .multiply(bd("20")).max(BigDecimal.ZERO).min(bd("20"));
        return momentum.add(liquidity).add(rangePosition).min(bd("100")).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 이번 스캔 후보들의 시세로 시장 분위기를 판단해요.
     * 평균 등락률 ≥ 0.7%이고 상승 비율 ≥ 55%면 RISK_ON, 평균 ≤ −0.7%이고 하락 비율 ≥ 55%면 RISK_OFF, 그 외 MIXED예요.
     * 랭킹 상위 종목만 본 것이라 시장 전체 분위기와는 다를 수 있어요.
     * 받는 것: 완전한 시세 목록.
     * 돌려주는 것: 분위기 상태와 평균 등락률, 상승/하락 비율, 평균 거래대금.
     */
    private RegimeResponse regime(List<BroadQuoteData> quotes) {
        long up = quotes.stream().filter(quote -> quote.changeRate().signum() > 0).count();
        long down = quotes.stream().filter(quote -> quote.changeRate().signum() < 0).count();
        BigDecimal averageChange = avg(quotes.stream().map(BroadQuoteData::changeRate).toList());
        BigDecimal averageValue = avg(quotes.stream().map(BroadQuoteData::tradingValue).toList());
        BigDecimal advanceRate = rate(up, quotes.size());
        BigDecimal declineRate = rate(down, quotes.size());
        String state = averageChange == null ? "UNKNOWN"
                : averageChange.compareTo(bd("0.7")) >= 0 && advanceRate.compareTo(bd("55")) >= 0 ? "RISK_ON"
                        : averageChange.compareTo(bd("-0.7")) <= 0 && declineRate.compareTo(bd("55")) >= 0 ? "RISK_OFF"
                                : "MIXED";
        return new RegimeResponse(state, averageChange, advanceRate, declineRate, averageValue);
    }

    /**
     * 하는 일: 숫자 목록의 평균을 소수 6자리로 계산해요.
     * 받는 것: 숫자 목록.
     * 돌려주는 것: 평균, 비어 있으면 null.
     */
    private BigDecimal avg(List<BigDecimal> values) {
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 백분율을 계산해요.
     * 받는 것: 개수, 전체 수.
     * 돌려주는 것: 퍼센트 값, 전체가 0이면 0.
     */
    private BigDecimal rate(long count, long total) {
        if (total == 0)
            return BigDecimal.ZERO;
        return BigDecimal.valueOf(count)
                .multiply(bd("100"))
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    /** 음수면 0으로 바꿔 돌려주는 도우미예요(하락은 모멘텀 점수에 넣지 않아요). */
    private static BigDecimal positive(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    /** 문자열로 BigDecimal을 만드는 짧은 도우미예요. */
    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** 후보 하나에 대한 시세 시도 결과예요(후보, 시세, 점수, 오류). */
    private record QuoteAttempt(BroadCandidate broad, BroadQuoteData quote, BigDecimal score, String error) {
    }
}
