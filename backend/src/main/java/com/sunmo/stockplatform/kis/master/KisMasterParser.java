/*
 * KIS 종목 마스터 파일(.mst, MS949 인코딩의 고정 길이 텍스트)을 한 줄씩 잘라 MasterStock 목록으로 바꾸는 파일이에요.
 * 앞부분에서 종목코드, 표준코드, 종목명을 읽고, 뒷부분의 고정 폭 칸에서 종목 유형, 거래정지, 관리종목 여부를 읽어요.
 * StockMasterSyncService가 내려받은 마스터 파일을 해석할 때 이 파서를 써요.
 */
package com.sunmo.stockplatform.kis.master;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 고정 길이 마스터 파일을 해석하는 파서예요.
 * 칸 너비가 정해진 서식지를 자로 재어 가며 "여기부터 여기까지는 이름" 하고 잘라 읽는 사무원과 같아요.
 * 시장(KOSPI/KOSDAQ)마다 뒷부분 칸 너비표가 달라서 따로 가지고 있어요.
 */
@Component
public class KisMasterParser {
    private static final Charset CP949 = Charset.forName("MS949");
    // 줄 뒷부분(suffix)의 칸 너비표예요. KIS 샘플 파서의 필드 순서를 따르는 것으로 보여요.
    private static final int[] KOSPI_WIDTHS = { 2, 1, 4, 4, 4, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 9, 5, 5, 1, 1, 1 };
    private static final int[] KOSDAQ_WIDTHS = { 2, 1, 4, 4, 4, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 9, 5, 5, 1, 1, 1 };

    /**
     * 마스터 파일 전체를 줄 단위로 해석해요.
     * 받는 것: .mst 파일 바이트, 시장. 돌려주는 것: 빈 줄을 뺀 모든 종목의 MasterStock 목록.
     * 줄이 너무 짧거나 칸 형식이 바뀌었으면 동기화 실패 예외를 던져요.
     */
    public List<MasterStock> parse(byte[] content, Market market) {
        // 준비: 한글 종목명을 위해 MS949로 읽고, 시장별 뒷부분 길이와 칸 너비표를 골라요.
        String text = new String(content, CP949);
        List<MasterStock> result = new ArrayList<>();
        // KIS sample lengths include LF; split below removes the line terminator.
        // (KIS 샘플의 길이 값은 줄바꿈 문자를 포함한 값이고, 아래 split이 줄바꿈을 없애 줘요.)
        int suffixLength = market == Market.KOSPI ? 227 : 221;
        int[] widths = market == Market.KOSPI ? KOSPI_WIDTHS : KOSDAQ_WIDTHS;
        for (String rawLine : text.split("\\R")) {
            if (rawLine.isBlank())
                continue;
            if (rawLine.length() <= suffixLength + 21) {
                throw invalid("KIS master line is shorter than expected for " + market);
            }
            // 처리: 줄을 앞부분(코드·이름, 길이가 가변)과 뒷부분(고정 길이 칸들)으로 나눠요.
            int suffixStart = rawLine.length() - suffixLength;
            String identity = rawLine.substring(0, suffixStart);
            String suffix = rawLine.substring(suffixStart);
            String stockCode = identity.substring(0, 9).trim();
            String standardCode = identity.substring(9, 21).trim();
            String stockName = identity.substring(21).trim();
            // 뒷부분에서 필요한 칸만 번호로 꺼내요. 칸 번호는 시장마다 달라요.
            String groupCode = field(suffix, widths, 0);
            String etpCode = field(suffix, widths, market == Market.KOSPI ? 12 : 8);
            boolean halted = "Y".equals(field(suffix, widths, market == Market.KOSPI ? 34 : 29));
            boolean managed = "Y".equals(field(suffix, widths, market == Market.KOSPI ? 36 : 31));
            result.add(new MasterStock(stockCode, standardCode, stockName, market,
                    marketType(groupCode, etpCode), managed, halted));
        }
        return result;
    }

    /**
     * 그룹 코드와 ETP 코드로 종목 유형을 정해요.
     * 받는 것: 증권 그룹 코드, ETP(상장지수상품) 구분 코드. 돌려주는 것: ETN, ETF, 그 외는 STOCK.
     */
    private MarketType marketType(String groupCode, String etpCode) {
        if ("3".equals(etpCode) || "4".equals(etpCode))
            return MarketType.ETN;
        if ("EF".equals(groupCode) || "FE".equals(groupCode) || "1".equals(etpCode) || "2".equals(etpCode)) {
            return MarketType.ETF;
        }
        return MarketType.STOCK;
    }

    /**
     * 뒷부분 문자열에서 index번째 칸을 잘라 공백을 지워요.
     * 받는 것: 뒷부분 문자열, 칸 너비표, 칸 번호. 돌려주는 것: 그 칸의 값, 길이가 모자라면 형식 변경 예외.
     */
    private String field(String suffix, int[] widths, int index) {
        int start = 0;
        for (int i = 0; i < index; i++)
            start += widths[i];
        int end = start + widths[index];
        if (end > suffix.length())
            throw invalid("KIS master suffix format changed");
        return suffix.substring(start, end).trim();
    }

    /**
     * 마스터 파일 형식 오류용 예외를 만들어요.
     * 받는 것: 설명 문구. 돌려주는 것: STOCK_MASTER_SYNC_FAILED, HTTP 502 예외.
     */
    private ApplicationException invalid(String message) {
        return new ApplicationException(ErrorCode.STOCK_MASTER_SYNC_FAILED, HttpStatus.BAD_GATEWAY, message);
    }
}
