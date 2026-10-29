/*
 * KIS 과거 날짜 분봉 조회가 얼마나 과거까지 되는지 확인하는 진단 API예요(연구용).
 * 정밀 종목 깔때기를 과거 장중 상태로 재현할 수 있는지 판단하려고 만들었어요. 운영 수집이나 점수에는 쓰지 않아요.
 */
package com.sunmo.stockplatform.candle.api;

import com.sunmo.stockplatform.kis.candle.KisMinuteCandleClient;
import com.sunmo.stockplatform.kis.candle.KisMinuteCandleClient.HistoryProbe;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;

/** 과거 분봉 조회 진단 API예요. */
@RestController
@RequestMapping("/api/v1/market-data/minute-history")
public class MinuteHistoryProbeController {
    private final KisMinuteCandleClient client;

    /** 하는 일: KIS 분봉 클라이언트를 주입받아요. */
    public MinuteHistoryProbeController(KisMinuteCandleClient client) { this.client = client; }

    /**
     * 하는 일: GET /probe?code=005930&date=2025-10-28&time=151500. 그 날짜·시각까지의 과거 분봉을 KIS에 한 번 조회해
     * 응답 상태와 받은 범위를 돌려줘요. 호출 1회예요.
     */
    @GetMapping("/probe")
    public HistoryProbe probe(@RequestParam String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "151500") String time) {
        return client.probeHistory(code, date, LocalTime.parse(time, java.time.format.DateTimeFormatter.ofPattern("HHmmss")));
    }
}
