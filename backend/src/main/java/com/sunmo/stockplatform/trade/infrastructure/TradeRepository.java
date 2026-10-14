/*
 * 거래 기록(trade) 테이블을 조회·저장하는 레포지토리(DB에서 데이터를 꺼내고 넣는 창구) 파일이에요.
 * Spring Data JPA가 메서드 이름을 보고 쿼리를 자동으로 만들어 줘요.
 * TradeService가 사용해요.
 */
package com.sunmo.stockplatform.trade.infrastructure;

import com.sunmo.stockplatform.trade.domain.Trade;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

/**
 * 거래 기록을 DB에서 찾아 주는 창구예요.
 * 가계부를 최신순으로 넘겨 보거나, 처음부터 순서대로 넘기거나, 특정 줄을 찾아 주는 장부 담당과 같아요.
 */
public interface TradeRepository extends JpaRepository<Trade, Long> {
    /** 사용자의 거래를 최신순으로 페이지 크기만큼 돌려줘요(화면 목록용). */
    List<Trade> findByOwnerIdOrderByTradedAtDescIdDesc(Long ownerId, Pageable pageable);

    /** 사용자의 거래 전체를 오래된 순으로 돌려줘요(손익 재계산·장부 검증용). */
    List<Trade> findByOwnerIdOrderByTradedAtAscIdAsc(Long ownerId);

    /** 거래 id와 사용자 id가 모두 맞는 거래 한 건을 찾아요(남의 거래 접근 방지). */
    Optional<Trade> findByIdAndOwnerId(Long id, Long ownerId);

    /** 같은 Idempotency-Key로 이미 저장된 거래가 있는지 찾아요(중복 저장 방지). */
    Optional<Trade> findByOwnerIdAndIdempotencyKey(Long ownerId, String key);
}
