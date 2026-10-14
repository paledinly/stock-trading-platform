/*
 * KisMasterParser가 KIS 종목 마스터 파일(고정 폭 텍스트, MS949 인코딩)에서 종목 정보와 상태 값을 읽는지 확인해요.
 * 폭 계산이 한 칸만 어긋나도 ETF·관리종목·거래정지 표시가 틀어지므로 중요해요.
 */
package com.sunmo.stockplatform.kis.master;

import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;

/** 종목 마스터 고정 폭 파싱을 점검하는 테스트예요. */
class KisMasterParserTest {
    /**
     * 코스피 줄에서 종목코드·이름·유형·관리종목·거래정지 값을 읽는지 확인해요.
     * 준비: 필드 폭 배열에 맞춰 ETF 구분(EF)과 상태 플래그를 넣은 줄을 만들고, 줄 끝을 \n, \r\n, 없음으로 바꿔 가며 파싱해요.
     * 기대: 매번 069500 KODEX 200, ETF, 관리종목·거래정지 true인 1건이에요.
     */
    @Test
    void parsesKospiIdentityAndStatusFields() {
        int[] widths = {2,1,4,4,4,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,9,5,5,1,1,1};
        String suffix = " ".repeat(227);
        suffix = replaceField(suffix, widths, 0, "EF");
        suffix = replaceField(suffix, widths, 12, "2");
        suffix = replaceField(suffix, widths, 34, "Y");
        suffix = replaceField(suffix, widths, 36, "Y");
        String line = fixed("069500", 9) + fixed("KR7069500007", 12) + "KODEX 200" + suffix;

        for (String ending : new String[]{"\n", "\r\n", ""}) {
            var parsed = new KisMasterParser().parse((line + ending).getBytes(Charset.forName("MS949")), Market.KOSPI);
            assertThat(parsed).singleElement().satisfies(stock -> {
                assertThat(stock.stockCode()).isEqualTo("069500");
                assertThat(stock.stockName()).isEqualTo("KODEX 200");
                assertThat(stock.marketType()).isEqualTo(MarketType.ETF);
                assertThat(stock.managed()).isTrue();
                assertThat(stock.tradingHalted()).isTrue();
            });
        }
    }

    /**
     * 코스닥 줄을 줄바꿈 문자 길이에 영향받지 않고 읽는지 확인해요.
     * 준비: 코스닥 폭 배열로 일반주식(ST)과 상태 플래그를 넣은 한글 이름 줄을 만들어요.
     * 기대: 줄 끝 형태와 관계없이 주성엔지니어링, STOCK, 관리종목·거래정지 true예요.
     */
    @Test void parsesKosdaqFieldsWithoutCountingLineTerminator() {
        int[] widths = {2,1,4,4,4,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,9,5,5,1,1,1};
        String suffix = " ".repeat(221);
        suffix = replaceField(suffix, widths, 0, "ST");
        suffix = replaceField(suffix, widths, 29, "Y");
        suffix = replaceField(suffix, widths, 31, "Y");
        String line = fixed("036930", 9) + fixed("KR7036930004", 12) + "주성엔지니어링" + suffix;
        for (String ending : new String[]{"\n", "\r\n", ""}) {
            var parsed = new KisMasterParser().parse((line + ending).getBytes(Charset.forName("MS949")), Market.KOSDAQ);
            assertThat(parsed).singleElement().satisfies(stock -> {
                assertThat(stock.stockName()).isEqualTo("주성엔지니어링");
                assertThat(stock.marketType()).isEqualTo(MarketType.STOCK);
                assertThat(stock.managed()).isTrue();
                assertThat(stock.tradingHalted()).isTrue();
            });
        }
    }

    /** 고정 폭 문자열에서 index번째 필드 자리에 값을 끼워 넣는 도우미예요. */
    private String replaceField(String source, int[] widths, int index, String value) {
        int start = 0;
        for (int i = 0; i < index; i++) start += widths[i];
        return source.substring(0, start) + fixed(value, widths[index]) + source.substring(start + widths[index]);
    }

    /** 값을 지정한 폭만큼 공백으로 채우는 도우미예요. */
    private String fixed(String value, int width) {
        return value + " ".repeat(width - value.length());
    }
}

