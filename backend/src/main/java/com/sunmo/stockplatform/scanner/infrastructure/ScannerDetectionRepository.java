/*
 * 감지 기록(ScannerDetection)을 DB에서 찾고 세는 레포지토리(DB 조회 담당) 파일이에요.
 * 대부분 메서드 이름 규칙으로 쿼리가 자동으로 만들어지고, 일부는 직접 쓴 JPQL을 써요.
 * 스캐너 조회 서비스, 성과 분석, 종가 추천, 커버리지 진단, 백테스트·백필 쪽에서 써요.
 */
package com.sunmo.stockplatform.scanner.infrastructure;

import com.sunmo.stockplatform.scanner.domain.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * 감지 기록 대장을 조건별로 찾아 주는 창구예요.
 * 도서관 검색대처럼 '이 종류, 이 시각 이후, 최신순' 같은 조건을 주면 맞는 기록을 꺼내 줘요.
 */
public interface ScannerDetectionRepository extends JpaRepository<ScannerDetection, Long> {
    /**
     * 하는 일: [from, to) 시간에 감지된 서로 다른 종목 수를 세요.
     * 받는 것: from(포함), to(제외).
     * 돌려주는 것: 종목 수.
     */
    @org.springframework.data.jpa.repository.Query("select count(distinct d.stock.id) from ScannerDetection d where d.detectedAt >= :from and d.detectedAt < :to")
    long countDistinctStocks(@org.springframework.data.repository.query.Param("from") Instant from,
            @org.springframework.data.repository.query.Param("to") Instant to);
    /**
     * 하는 일: 한 종류의 감지를 최신순으로 가져와요.
     * 받는 것: type, pageable(개수 제한).
     * 돌려주는 것: 감지 목록.
     */
    List<ScannerDetection> findByTypeOrderByDetectedAtDesc(ScannerType type, Pageable pageable);

    /**
     * 하는 일: 모든 감지를 최신순으로 가져와요.
     * 받는 것: pageable.
     * 돌려주는 것: 감지 목록.
     */
    List<ScannerDetection> findAllByOrderByDetectedAtDesc(Pageable pageable);

    /**
     * 하는 일: 한 종류에서 from 이후 감지를 최신순으로 가져와요.
     * 받는 것: type, from, pageable.
     * 돌려주는 것: 감지 목록.
     */
    List<ScannerDetection> findByTypeAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(ScannerType type,
            Instant from, Pageable pageable);

    /**
     * 하는 일: from 이후 감지를 최신순으로 가져와요.
     * 받는 것: from, pageable.
     * 돌려주는 것: 감지 목록.
     */
    List<ScannerDetection> findByDetectedAtGreaterThanEqualOrderByDetectedAtDesc(Instant from, Pageable pageable);

    /**
     * 하는 일: 해당 장 날짜에서 from 이후 감지된 종목코드를 최신순으로 가져와요(같은 종목이 여러 번 나올 수 있어요).
     * 받는 것: date(장 날짜), from, pageable.
     * 돌려주는 것: 종목코드 목록.
     */
    @org.springframework.data.jpa.repository.Query("select d.stock.stockCode from ScannerDetection d where d.sessionDate = :date and d.detectedAt >= :from order by d.detectedAt desc")
    List<String> findRecentStockCodesForSession(
            @org.springframework.data.repository.query.Param("date") LocalDate date,
            @org.springframework.data.repository.query.Param("from") Instant from, Pageable pageable);

    /**
     * 하는 일: id로 감지 한 건을 찾아요(기본 기능을 다시 적어 둔 것이에요).
     * 받는 것: id.
     * 돌려주는 것: 있으면 감지, 없으면 빈 Optional.
     */
    Optional<ScannerDetection> findById(Long id);

    /**
     * 하는 일: from~to 사이(양 끝 포함) 감지를 오래된 순으로 가져와요.
     * 받는 것: from, to.
     * 돌려주는 것: 감지 목록.
     */
    List<ScannerDetection> findByDetectedAtBetweenOrderByDetectedAtAsc(Instant from, Instant to);

    /**
     * 하는 일: 해당 장 날짜에서 from 이후 감지를 개수 제한 없이 최신순으로 가져와요.
     * 받는 것: sessionDate, from.
     * 돌려주는 것: 감지 목록.
     */
    List<ScannerDetection> findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(
            LocalDate sessionDate, Instant from);
}
