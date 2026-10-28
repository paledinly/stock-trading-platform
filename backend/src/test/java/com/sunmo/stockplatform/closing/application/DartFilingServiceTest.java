/*
 * DartFilingService 가 DART(전자공시) 목록 API 를 날짜·페이지별로 훑어 상장사 공시만 저장하는 흐름을 확인해요.
 * 후보 경고 상태와 함께 나중에 가설을 검증할 공시 기록이므로, 빠짐없이 모으되 한도 오류에서는 무리하게 재시도하지 않는지가 중요해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 공시 수집기가 필요한 공시만 저장하고 오류에서 멈출 줄 아는지 확인하는 테스트예요. */
class DartFilingServiceTest {
    final ObjectMapper json = new ObjectMapper();
    /** DB 저장 호출을 기록만 하는 목(mock, 가짜 객체)이에요. */
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    /** 고정 시계 Clock(2026-10-02 18:10 한국시간)을 쓰는 달력과 가짜 페이지 공급자로 서비스를 만들어요. */
    DartFilingService service(DartFilingService.PageSource pages) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse("2026-10-02T09:10:00Z"), ZoneOffset.UTC));
        return new DartFilingService("key", calendar, jdbc, pages);
    }

    /**
     * 무엇: 여러 날짜·페이지를 순회하며 상장사(유가 Y, 코스닥 K) 공시만 저장하고, 자료 없음(013) 날짜는 넘어가는지 확인해요.
     * 입력: 10/02 만 2페이지 공시(삼성전자, 비상장 E, 코스닥사)가 있고 나머지 날짜는 status 013 인 응답.
     * 기대: 2건 저장, 상태 OK, 호출 순서 9/29~10/02 각 1페이지 + 10/02 2페이지, 저장 종목은 005930, 035720.
     */
    @Test
    void storesListedCompanyFilingsAcrossPagesAndSkipsEmptyDates() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        List<String> calls = new ArrayList<>();
        var result = service((date, corpCls, page) -> {
            calls.add(date + "#" + page);
            try {
                if (!date.equals(LocalDate.of(2026, 10, 2)))
                    return json.readTree("{\"status\":\"013\",\"message\":\"no data\"}");
                return json.readTree(page == 1 ? """
                        {"status":"000","total_page":2,"list":[
                          {"corp_cls":"Y","corp_code":"00126380","corp_name":"삼성전자","stock_code":"005930",
                           "report_nm":"유상증자결정","rcept_no":"20261002000001","flr_nm":"삼성전자","rcept_dt":"20261002","rm":"유"},
                          {"corp_cls":"E","corp_code":"00000001","corp_name":"비상장","stock_code":" ",
                           "report_nm":"감사보고서","rcept_no":"20261002000002","flr_nm":"x","rcept_dt":"20261002","rm":""}]}
                        """ : """
                        {"status":"000","total_page":2,"list":[
                          {"corp_cls":"K","corp_code":"00000002","corp_name":"코스닥사","stock_code":"035720",
                           "report_nm":"전환사채권발행결정","rcept_no":"20261002000003","flr_nm":"코스닥사","rcept_dt":"20261002","rm":"코"}]}
                        """);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }).collect();

        // 확인: 비상장(E, 종목코드 공백) 공시는 저장하지 않아요.
        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(calls).containsExactly("2026-09-29#1", "2026-09-30#1", "2026-10-01#1", "2026-10-02#1", "2026-10-02#2");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), args.capture());
        assertThat(args.getAllValues()).extracting(row -> (String) row[5]).containsExactly("005930", "035720");
    }

    /**
     * 무엇: 예상하지 못한 상태 코드(020, 호출 한도)를 받으면 다른 날짜·페이지를 계속 두드리지 않고 멈추는지 확인해요.
     * 입력: 모든 요청에 status 020 을 돌려주는 페이지 공급자.
     * 기대: 상태 DART_STATUS_020, 요청 1페이지, DB 저장 호출 없음.
     */
    @Test
    void stopsOnAnUnexpectedStatusWithoutRetryingEveryPage() throws Exception {
        JsonNode denied = json.readTree("{\"status\":\"020\",\"message\":\"limit\"}");
        var result = service((date, corpCls, page) -> denied).collect();
        assertThat(result.status()).isEqualTo("DART_STATUS_020");
        assertThat(result.pages()).isEqualTo(1);
        verifyNoInteractions(jdbc);
    }

    /**
     * 무엇: 과거 수집이 최신 날짜부터 거꾸로, 상장 시장(Y·K)별로 조회하고 묶음으로 저장하는지 확인해요.
     * 입력: 10/02 Y에만 공시 1건, 나머지(10/02 K, 10/01 Y·K)는 013.
     * 기대: 호출 순서 10/02 Y, 10/02 K, 10/01 Y, 10/01 K. 1건 저장, 2일 완료, 마지막 날짜 10/01.
     */
    @Test
    void backfillWalksBackwardsByListedMarket() throws Exception {
        when(jdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[] { 1 });
        JsonNode filing = json.readTree("""
                {"status":"000","total_page":1,"list":[{"rcept_no":"20261002000001","rcept_dt":"20261002",
                "corp_cls":"Y","corp_code":"00126380","corp_name":"A","stock_code":"005930","report_nm":"r","flr_nm":"A","rm":""}]}""");
        JsonNode empty = json.readTree("{\"status\":\"013\"}");
        List<String> calls = new java.util.ArrayList<>();
        DartFilingService service = service((date, corpCls, page) -> {
            calls.add(date + ":" + corpCls);
            return date.equals(java.time.LocalDate.of(2026, 10, 2)) && "Y".equals(corpCls) ? filing : empty;
        });
        service.pauseMillis = 0;

        var status = service.backfill(java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 2));

        assertThat(calls).containsExactly("2026-10-02:Y", "2026-10-02:K", "2026-10-01:Y", "2026-10-01:K");
        assertThat(status.saved()).isEqualTo(1);
        assertThat(status.days()).isEqualTo(2);
        assertThat(status.lastDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 1));
        assertThat(status.stoppedReason()).isNull();
    }

    /**
     * 무엇: DART가 오류 상태(020 호출 한도 초과)를 주면 과거 수집이 그 자리에서 멈추고 이유를 남기는지 확인해요.
     */
    @Test
    void backfillStopsOnDartError() throws Exception {
        JsonNode limited = json.readTree("{\"status\":\"020\"}");
        DartFilingService service = service((date, corpCls, page) -> limited);
        service.pauseMillis = 0;

        var status = service.backfill(java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 10, 2));

        assertThat(status.stoppedReason()).isEqualTo("DART_STATUS_020");
        assertThat(status.calls()).isEqualTo(1);
        assertThat(status.lastDate()).isNull();
    }
}
