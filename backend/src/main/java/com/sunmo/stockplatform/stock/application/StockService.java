/*
 * 종목 검색과 종목코드로 종목 한 건 조회를 담당하는 파일이에요.
 * 잘못된 검색어나 없는 종목은 정해진 오류로 바꿔 알려 줘요.
 * StockController와 관심종목·시세 등 다른 기능이 종목을 찾을 때 사용해요.
 */
package com.sunmo.stockplatform.stock.application;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 종목을 찾아 주는 서비스예요.
 * 도서관 검색 단말기처럼 제목 일부로 책 목록을 찾거나, 청구기호로 책 한 권을 바로 찾아 줘요.
 * 읽기 전용 트랜잭션(데이터를 바꾸지 않는 조회 묶음)으로 동작해요.
 */
@Service
@Transactional(readOnly = true)
public class StockService {
    private final StockRepository stockRepository;

    /**
     * 종목 레포지토리(DB 조회 창구)를 주입받아요.
     * 받는 것: 종목 레포지토리. 돌려주는 것: 없음(생성자).
     */
    public StockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    /**
     * 이름이나 코드 일부로 활성 종목을 검색해요.
     * 받는 것: 검색어, 최대 개수. 돌려주는 것: 정확도 순으로 정렬된 종목 목록.
     * 검색어가 비어 있으면 400 오류를 던지고, 개수는 1~50 사이로 맞춰요.
     */
    public List<Stock> search(String query, int limit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "Search query must not be blank");
        }
        return stockRepository.search(normalized, PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));
    }

    /**
     * 종목코드로 활성 종목 한 건을 찾아요.
     * 받는 것: 종목코드. 돌려주는 것: 종목(없거나 비활성이면 404 오류).
     */
    public Stock getByCode(String stockCode) {
        return stockRepository.findByStockCodeAndActiveTrue(stockCode)
                .orElseThrow(() -> new ApplicationException(ErrorCode.STOCK_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Stock not found: " + stockCode));
    }
}
