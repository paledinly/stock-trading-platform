/*
 * Trajectory·미세구조·수급 연구 데이터를 조회하는 REST API(웹이 URL로 요청하고 JSON으로 답을 받는 통로)예요.
 * /api/v1/closing-trajectory 아래에서 수집 상태, 1분 집계, 스냅샷, 수급 관측, 오전 결과 조회와 오전 결과 계산 실행을 제공해요.
 * 연구·점검용이고 추천 점수에는 쓰지 않아요. 날짜는 한국 시간(ClosingTradingCalendar.ZONE) 하루 범위로 바꿔 조회해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import static com.sunmo.stockplatform.closing.application.ClosingTradingCalendar.ZONE;

/**
 * 연구 데이터 조회 요청을 받아 저장소·서비스로 넘기는 컨트롤러(웹 요청을 받아 알맞은 곳으로 연결하는 창구)예요.
 * 도서관 열람 창구처럼 "이 종목, 이 날짜 기록 보여 주세요"라는 요청에 보관된 기록을 그대로 꺼내 줘요.
 */
@RestController
@RequestMapping("/api/v1/closing-trajectory")
public class TrajectoryController {
    private final TrajectoryStore store;
    private final TrajectoryService service;
    private final MorningOutcomeService outcomes;
    private final InvestorFlowCollector flow;
    private final MicrostructureStore microStore;
    private final MicrostructureService microService;
    private final OrderbookCollector books;
    /** 하는 일: 필요한 저장소·서비스·수집기를 받아 보관해요. 받는 것: 각 의존 객체. 돌려주는 것: 컨트롤러 객체예요. */
    public TrajectoryController(TrajectoryStore store, TrajectoryService service, MorningOutcomeService outcomes, InvestorFlowCollector flow,
            MicrostructureStore microStore, MicrostructureService microService, OrderbookCollector books) {
        this.store = store; this.service = service; this.outcomes = outcomes; this.flow = flow;
        this.microStore=microStore;this.microService=microService;this.books=books;
    }
    /** 하는 일: GET /microstructure/status. 미세구조 집계와 호가 수집기의 상태를 돌려줘요. 받는 것: 없어요. 돌려주는 것: 상태 Map이에요. */
    @GetMapping("/microstructure/status") public Map<String,Object> microStatus() {
        return TrajectoryModel.fields("aggregation",microService.status(),"orderbook",books.status());
    }
    /** 하는 일: GET /microstructure/minutes. 한 종목의 하루치 미세구조 1분 집계를 돌려줘요. 받는 것: symbol, date. 돌려주는 것: 1분 목록이에요. */
    @GetMapping("/microstructure/minutes") public List<MicrostructureModel.Minute> microMinutes(@RequestParam String symbol,@RequestParam LocalDate date) {
        return microStore.minutes(symbol,date.atStartOfDay(ZONE).toInstant(),date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }
    /** 하는 일: GET /flow/status. 수급 수집기 상태를 돌려줘요. 받는 것: 없어요. 돌려주는 것: 상태 Map이에요. */
    @GetMapping("/flow/status") public Map<String, Object> flowStatus() { return flow.status(); }
    /**
     * 하는 일: GET /flow. 한 종목의 하루치 수급(외국인·기관·프로그램 순매수) 관측만 골라 돌려줘요.
     * 저장소 조회는 끝 시각을 포함하므로, 다음 날 00:00 정각 관측은 다시 걸러 내요.
     * 받는 것: symbol, date. 돌려주는 것: *_NET_BUY 종류의 Context 목록이에요.
     */
    @GetMapping("/flow") public List<TrajectoryModel.Context> flow(@RequestParam String symbol, @RequestParam LocalDate date) {
        return store.contexts(symbol, symbol, date.atStartOfDay(ZONE).toInstant(), date.plusDays(1).atStartOfDay(ZONE).toInstant())
                .stream().filter(r -> r.kind().endsWith("_NET_BUY") && r.receivedAt().isBefore(date.plusDays(1).atStartOfDay(ZONE).toInstant())).toList();
    }
    /** 하는 일: GET /status. Trajectory 수집 상태(켜짐 여부, 거부 틱 수, 버린 맥락 수, 오류)를 돌려줘요. */
    @GetMapping("/status") public Map<String, Object> status() { return service.status(); }
    /** 하는 일: GET /minutes. 한 종목의 하루치 1분 집계를 돌려줘요. 받는 것: symbol, date. 돌려주는 것: Minute 목록이에요. */
    @GetMapping("/minutes") public List<TrajectoryModel.Minute> minutes(@RequestParam String symbol, @RequestParam LocalDate date) {
        return store.minutes(symbol, date.atStartOfDay(ZONE).toInstant(), date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }
    /** 하는 일: GET /snapshots. 그날 모든 종목의 스냅샷을 돌려줘요. 받는 것: date. 돌려주는 것: Snapshot 목록이에요. */
    @GetMapping("/snapshots") public List<TrajectoryModel.Snapshot> snapshots(@RequestParam LocalDate date) {
        return store.snapshots(date.atStartOfDay(ZONE).toInstant(), date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }
    /** 하는 일: GET /outcomes. 그날 스냅샷들에 채워진 다음 날 오전 결과를 돌려줘요. 받는 것: date. 돌려주는 것: 결과 목록이에요. */
    @GetMapping("/outcomes") public List<Map<String, Object>> outcomes(@RequestParam LocalDate date) {
        return store.outcomes(date.atStartOfDay(ZONE).toInstant(), date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }
    /** 하는 일: POST /outcomes/track. 그날 스냅샷들의 다음 날 오전 결과를 계산해 채워요. 받는 것: date. 돌려주는 것: 처리한 개수(tracked)예요. */
    @PostMapping("/outcomes/track") public Map<String, Integer> track(@RequestParam LocalDate date) {
        return Map.of("tracked", outcomes.track(date));
    }
}
