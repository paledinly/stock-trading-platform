/*
 * 종목(stock) 테이블을 조회하고 저장하는 레포지토리(DB에서 데이터를 꺼내고 넣는 창구) 파일이에요.
 * Spring Data JPA가 메서드 이름과 @Query를 보고 실제 SQL을 자동으로 만들어 줘요.
 * 종목 검색, 마스터 동기화, 시장 전체 스캔 대상 선정, 거래 기록 등에서 사용해요.
 */
package com.sunmo.stockplatform.stock.infrastructure;

import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.domain.Market;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

/**
 * 종목 정보를 DB에서 찾아 주는 창구예요.
 * 도서관 사서처럼 "이 번호 책", "이 제목이 들어간 책", "대출 가능한 일반 도서 전부" 같은 요청에 맞춰 목록을 꺼내 줘요.
 */
public interface StockRepository extends JpaRepository<Stock, Long> {
  /** 종목코드로 활성 종목 한 건을 찾아요. 받는 것: 종목코드. 돌려주는 것: 있으면 종목, 없으면 빈 값. */
  Optional<Stock> findByStockCodeAndActiveTrue(String stockCode);

  /** 활성 여부와 상관없이 종목코드로 한 건을 찾아요(마스터 동기화용). 받는 것: 종목코드. 돌려주는 것: 종목 또는 빈 값. */
  Optional<Stock> findByStockCode(String stockCode);

  /** 여러 종목코드에 해당하는 종목을 한 번에 찾아요. 받는 것: 종목코드 묶음. 돌려주는 것: 찾은 종목 목록. */
  List<Stock> findByStockCodeIn(Collection<String> stockCodes);

  /**
   * 이름(대소문자 무시, 부분 일치)이나 코드(앞부분 일치)로 활성 종목을 검색해요.
   * 받는 것: 검색어, 페이지(개수 제한). 돌려주는 것: 종목 목록.
   * 정렬 순서: 코드 정확 일치 → 이름 정확 일치 → 이름 앞부분 일치 → 나머지, 그다음 이름이 짧은 순이에요.
   */
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

  /**
   * 시장 전체 스캔(Broad)에 넣을 종목 범위(Universe)를 골라요.
   * 활성이면서 관리종목·거래정지가 아닌 종목만 고르고, 시장 지정이 없으면(null) 전체 시장을 봐요.
   * 받는 것: 시장(없으면 전체), ETF/ETN 포함 여부, 페이지. 돌려주는 것: 시장·코드 순으로 정렬된 종목 목록.
   */
  @Query("""
      select s from Stock s
       where s.active = true
         and s.managed = false
         and s.tradingHalted = false
         and (:market is null or s.market = :market)
         and (:includeEtf = true or (s.etf = false and s.etn = false))
       order by s.market asc, s.stockCode asc
      """)
  List<Stock> broadScanUniverse(@Param("market") Market market,
      @Param("includeEtf") boolean includeEtf,
      Pageable pageable);

  /** ETF/ETN을 뺀 활성 종목 전체를 코드 순으로 돌려줘요. 받는 것: 없음. 돌려주는 것: 종목 목록. */
  List<Stock> findByActiveTrueAndEtfFalseAndEtnFalseOrderByStockCodeAsc();

  // 아래 count 메서드들은 조건별 종목 수를 세요(진단·완결성 지표용으로 보여요). 받는 것은 없고 개수를 돌려줘요.

  /** 활성 종목 수예요. */
  long countByActiveTrue();

  /** 활성이면서 관리종목·거래정지가 아닌 종목 수예요. */
  long countByActiveTrueAndManagedFalseAndTradingHaltedFalse();

  /** 위 조건에 더해 ETF/ETN까지 뺀 일반 주식 수예요. */
  long countByActiveTrueAndManagedFalseAndTradingHaltedFalseAndEtfFalseAndEtnFalse();
}
