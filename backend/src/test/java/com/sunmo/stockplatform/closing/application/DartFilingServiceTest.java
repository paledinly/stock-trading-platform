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

class DartFilingServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    DartFilingService service(DartFilingService.PageSource pages) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse("2026-10-02T09:10:00Z"), ZoneOffset.UTC));
        return new DartFilingService("key", calendar, jdbc, pages);
    }

    @Test
    void storesListedCompanyFilingsAcrossPagesAndSkipsEmptyDates() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        List<String> calls = new ArrayList<>();
        var result = service((date, page) -> {
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

        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(calls).containsExactly("2026-09-29#1", "2026-09-30#1", "2026-10-01#1", "2026-10-02#1", "2026-10-02#2");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), args.capture());
        assertThat(args.getAllValues()).extracting(row -> (String) row[5]).containsExactly("005930", "035720");
    }

    @Test
    void stopsOnAnUnexpectedStatusWithoutRetryingEveryPage() throws Exception {
        JsonNode denied = json.readTree("{\"status\":\"020\",\"message\":\"limit\"}");
        var result = service((date, page) -> denied).collect();
        assertThat(result.status()).isEqualTo("DART_STATUS_020");
        assertThat(result.pages()).isEqualTo(1);
        verifyNoInteractions(jdbc);
    }
}
