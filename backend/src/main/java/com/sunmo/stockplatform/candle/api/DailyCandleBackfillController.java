/*
 * 장마감 추천에 필요한 일봉을 지금 바로 준비(보충)하라고 요청하는 API예요.
 * 평소에는 08:10과 14:25/14:35 스케줄로 돌지만, 운영자가 수동으로도 실행할 수 있어요.
 * 운영 화면이나 운영자가 POST /api/v1/closing-recommendations/daily-data/prepare로 호출해요.
 */
package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.candle.application.DailyCandleBackfillService;
import com.sunmo.stockplatform.candle.application.DailyCandleBackfillService.PrepareResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 일봉 준비 수동 실행 버튼이에요. 정해진 시간에 오는 배달 말고 "지금 한 번 더 채워 주세요" 하고 부르는 창구예요.
 */
@RestController
@RequestMapping("/api/v1/closing-recommendations/daily-data")
public class DailyCandleBackfillController {
    private final DailyCandleBackfillService service;

    /** 하는 일: 일봉 보충 서비스를 주입받아요(의존성 주입). */
    public DailyCandleBackfillController(DailyCandleBackfillService service) { this.service = service; }

    /**
     * 하는 일: 일봉 준비를 즉시 실행해요(끝날 때까지 기다려요).
     * 돌려주는 것: 대상 추천일, 시도·저장·준비 완료·실패 종목 수 등 결과 요약.
     */
    @PostMapping("/prepare")
    public PrepareResult prepare() { return service.prepare(); }
}
