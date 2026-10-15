/*
 * 장마감 자동 추천이 거래일마다 제대로 돌았는지(일별 건강 상태)를 보여 주는 REST API(웹이 URL로 요청하고 JSON으로 답을 받는 통로)예요.
 * GET /api/v1/closing-recommendations/daily-health 요청을 ClosingDailyHealthService로, daily-collection 요청을
 * DailyCollectionStatusService(하루 자동 수집 개수 조회)로 넘겨요.
 * 웹 운영 화면이 일별 실행 누락 여부를 확인할 때 사용하는 것으로 보여요.
 */
package com.sunmo.stockplatform.closing.api;

import com.sunmo.stockplatform.closing.application.ClosingDailyHealthService;
import com.sunmo.stockplatform.closing.application.DailyCollectionStatusService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 일별 건강 상태 조회 요청을 받아 서비스에 넘기는 컨트롤러(웹 요청을 받아 알맞은 서비스로 연결하는 창구)예요.
 * 출석부처럼 날짜별로 "왔음/결석(MISSING)"을 최신 날짜부터 보여 줘요.
 */
@Validated
@RestController
@RequestMapping("/api/v1/closing-recommendations")
public class ClosingDailyHealthController {
    private final ClosingDailyHealthService health;
    private final DailyCollectionStatusService collection;

    /** 하는 일: 필요한 서비스를 받아 보관해요. 받는 것: 일별 건강 서비스. 돌려주는 것: 컨트롤러 객체예요. */
    public ClosingDailyHealthController(ClosingDailyHealthService health, DailyCollectionStatusService collection) {
        this.health = health;
        this.collection = collection;
    }

    /** Newest first; trading days without a recorded row are returned as MISSING. */
    /*
     * 하는 일: 기간 안 거래일의 일별 상태를 최신순으로 돌려줘요. 기록이 없는 거래일은 MISSING으로 채워요.
     * 받는 것: from, to 날짜(선택, ISO 형식)예요. 비우면 서비스 기본 기간을 써요.
     * 돌려주는 것: 날짜별 DailyHealth 목록이에요.
     */
    @GetMapping("/daily-health")
    public List<ClosingDailyHealthService.DailyHealth> dailyHealth(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return health.list(from, to);
    }

    /**
     * 하는 일: 하루 동안 자동 수집된 장마감 자료(공식 run, 가상 진입, 상한가 감시, 후보 맥락, 15:25 종가 봉,
     * 전일 후보 관측·분봉 보완, DART 공시)의 개수와 마지막 시각을 돌려줘요. 조회만 하고 아무것도 저장하지 않아요.
     * 받는 것: date(선택, ISO 날짜, 없으면 오늘). 돌려주는 것: 하루 수집 현황이에요.
     */
    @GetMapping("/daily-collection")
    public DailyCollectionStatusService.DailyCollection dailyCollection(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return collection.status(date);
    }
}
