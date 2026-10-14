/*
 * 실시간 WebSocket을 지금 연결해도 되는지, 들어온 틱을 정규장 데이터로 받아도 되는지 판단하는 규칙 모음이에요.
 * 거래일·장 시작·장 마감 시각은 ClosingTradingCalendar 정책을 따르고, 15:30 종가 단일가 체결(최대 15:31:00)까지 받아요.
 * KIS 실시간 클라이언트(kis/websocket)가 연결 여부와 틱 수락 여부를 정할 때 사용해요.
 */
package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.config.RealtimeSessionProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.springframework.stereotype.Component;

/**
 * 정규장 시간 문지기예요.
 * 놀이공원 입장 게이트처럼 개장 조금 전부터 문을 열고(연결 허용), 입장권(틱)의 날짜·시각이 운영 시간 안인지 확인해요.
 * 틱 판단은 서버 시계가 아니라 틱의 원천 체결시각(occurredAt)을 기준으로 해요.
 */
@Component
public class RealtimeSessionPolicy {
    private final ClosingTradingCalendar calendar;
    private final RealtimeSessionProperties properties;
    /**
     * 하는 일: 거래일 달력과 연결 여유 시간 설정을 받아 둬요.
     * 받는 것: ClosingTradingCalendar, 장 전 연결·장 후 해제 여유 시간 설정.
     */
    public RealtimeSessionPolicy(ClosingTradingCalendar calendar, RealtimeSessionProperties properties) {
        this.calendar = calendar; this.properties = properties;
    }
    /**
     * 하는 일: 지금 KIS WebSocket(서버와 계속 연결된 채 실시간 데이터를 주고받는 통로)에 연결해 있어도 되는지 판단해요.
     * 받는 것: 없음(달력의 현재 시각을 써요).
     * 돌려주는 것: 거래일이고 [장 시작 - 연결 여유, 장 마감 + 해제 여유) 안이면 true.
     */
    public boolean connectionAllowed() {
        var now = calendar.now();
        var date = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        return calendar.isTradingDay(date)
                && !now.isBefore(calendar.open(date).minus(properties.connectBeforeOpen()))
                && now.isBefore(calendar.close(date).plus(properties.disconnectAfterClose()));
    }
    /**
     * Regular session 09:00..15:30 plus the random-end closing auction print (source time up to 15:31:00).
     * 하는 일: 틱이 오늘 정규장(09:00~15:30)과 종가 단일가 체결 구간(~15:31:00) 안의 것인지 확인해요.
     * 받는 것: 틱. 돌려주는 것: 받아도 되면 true. 장후 시간외(15:40~) 체결은 false예요.
     */
    public boolean accepts(MarketTick tick) {
        var date = tick.businessDate();
        return date.equals(calendar.today()) && calendar.isTradingDay(date)
                && !tick.occurredAt().isBefore(calendar.open(date))
                && !tick.occurredAt().isAfter(closingAuctionDeadline(date));
    }
    /**
     * True for an accepted tick stamped after 15:30:00: the closing single-price execution.
     * 하는 일: 15:30:00 이후~15:31:00 이하로 찍힌 틱, 즉 종가 단일가 체결인지 확인해요(진단 카운터용).
     * 받는 것: 틱. 돌려주는 것: 종가 단일가 체결이면 true.
     */
    public boolean closingAuctionPrint(MarketTick tick) {
        return tick.occurredAt().isAfter(calendar.close(tick.businessDate()))
                && !tick.occurredAt().isAfter(closingAuctionDeadline(tick.businessDate()));
    }
    /**
     * 하는 일: 그날 종가 단일가 체결을 받아 줄 마지막 시각(장 마감 + 60초)을 계산해요.
     * 받는 것: 거래일. 돌려주는 것: 마감 기한 시각.
     */
    private java.time.Instant closingAuctionDeadline(java.time.LocalDate date) {
        return calendar.close(date).plus(FiveMinuteCandleAggregator.CLOSING_AUCTION_WINDOW);
    }
}
