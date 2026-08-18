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

@Service
@Transactional(readOnly = true)
public class StockService {
    private final StockRepository stockRepository;

    public StockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public List<Stock> search(String query, int limit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "Search query must not be blank");
        }
        return stockRepository.search(normalized, PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));
    }

    public Stock getByCode(String stockCode) {
        return stockRepository.findByStockCodeAndActiveTrue(stockCode)
                .orElseThrow(() -> new ApplicationException(ErrorCode.STOCK_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Stock not found: " + stockCode));
    }
}

