/*
 * AI 장전 계획 2단계(실시간 감시·가상 매수) 조회 API예요. Web 'AI 장전' 화면의 '실시간 감시' 탭이 불러요.
 * 상태 판단과 기록은 AiWatchService가 장중에 스스로 해요. 이 API는 읽기만 해요.
 */
package com.sunmo.stockplatform.aiplan.api;

import com.sunmo.stockplatform.aiplan.realtime.AiWatchService;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 실시간 감시 조회 창구예요. */
@RestController
@RequestMapping("/api/v1/ai-plans/watch")
public class AiWatchController {
    private final AiWatchService watch;
    private final ClosingTradingCalendar calendar;

    public AiWatchController(AiWatchService watch, ClosingTradingCalendar calendar) {
        this.watch = watch;
        this.calendar = calendar;
    }

    /** 하는 일: GET /?date. 그날 종목별 상태·지표·조건·가상 포지션·상태 이력이에요. 기본은 오늘이에요. */
    @GetMapping
    public AiWatchService.WatchView view(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return watch.view(date == null ? calendar.today() : date);
    }

    /** 하는 일: GET /summary. 규칙·청산별 가상 거래 집계와 2단계 판정이에요. */
    @GetMapping("/summary")
    public AiWatchService.Summary summary() {
        return watch.summary();
    }
}
