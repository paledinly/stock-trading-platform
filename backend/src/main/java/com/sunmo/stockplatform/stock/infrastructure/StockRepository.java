package com.sunmo.stockplatform.stock.infrastructure;

import com.sunmo.stockplatform.stock.domain.Stock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findByStockCodeAndActiveTrue(String stockCode);

    Optional<Stock> findByStockCode(String stockCode);

    @Query("""
            select s from Stock s
             where s.active = true
               and (lower(s.stockName) like lower(concat('%', :query, '%'))
                    or s.stockCode like concat(:query, '%'))
             order by case
                        when s.stockCode = :query then 0
                        when lower(s.stockName) = lower(:query) then 1
                        when lower(s.stockName) like lower(concat(:query, '%')) then 2
                        else 3
                      end,
                      length(s.stockName), s.stockName
            """)
    List<Stock> search(@Param("query") String query, Pageable pageable);
}

