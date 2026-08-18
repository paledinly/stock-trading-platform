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

@Component
public class KisMasterParser {
    private static final Charset CP949 = Charset.forName("MS949");
    private static final int[] KOSPI_WIDTHS = {2,1,4,4,4,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,9,5,5,1,1,1};
    private static final int[] KOSDAQ_WIDTHS = {2,1,4,4,4,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,9,5,5,1,1,1};

    public List<MasterStock> parse(byte[] content, Market market) {
        String text = new String(content, CP949);
        List<MasterStock> result = new ArrayList<>();
        int suffixLength = market == Market.KOSPI ? 228 : 222;
        int[] widths = market == Market.KOSPI ? KOSPI_WIDTHS : KOSDAQ_WIDTHS;
        for (String rawLine : text.split("\\R")) {
            if (rawLine.isBlank()) continue;
            if (rawLine.length() <= suffixLength + 21) {
                throw invalid("KIS master line is shorter than expected for " + market);
            }
            int suffixStart = rawLine.length() - suffixLength;
            String identity = rawLine.substring(0, suffixStart);
            String suffix = rawLine.substring(suffixStart);
            String stockCode = identity.substring(0, 9).trim();
            String standardCode = identity.substring(9, 21).trim();
            String stockName = identity.substring(21).trim();
            String groupCode = field(suffix, widths, 0);
            String etpCode = field(suffix, widths, market == Market.KOSPI ? 12 : 8);
            boolean halted = "Y".equals(field(suffix, widths, market == Market.KOSPI ? 34 : 29));
            boolean managed = "Y".equals(field(suffix, widths, market == Market.KOSPI ? 36 : 31));
            result.add(new MasterStock(stockCode, standardCode, stockName, market,
                    marketType(groupCode, etpCode), managed, halted));
        }
        return result;
    }

    private MarketType marketType(String groupCode, String etpCode) {
        if ("3".equals(etpCode) || "4".equals(etpCode)) return MarketType.ETN;
        if ("EF".equals(groupCode) || "FE".equals(groupCode) || "1".equals(etpCode) || "2".equals(etpCode)) {
            return MarketType.ETF;
        }
        return MarketType.STOCK;
    }

    private String field(String suffix, int[] widths, int index) {
        int start = 0;
        for (int i = 0; i < index; i++) start += widths[i];
        int end = start + widths[index];
        if (end > suffix.length()) throw invalid("KIS master suffix format changed");
        return suffix.substring(start, end).trim();
    }

    private ApplicationException invalid(String message) {
        return new ApplicationException(ErrorCode.STOCK_MASTER_SYNC_FAILED, HttpStatus.BAD_GATEWAY, message);
    }
}

