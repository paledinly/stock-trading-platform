/*
 * 투자 노트(investment_journal) 테이블을 조회·저장하는 레포지토리(DB에서 데이터를 꺼내고 넣는 창구) 파일이에요.
 * Spring Data JPA가 메서드 이름을 보고 쿼리를 자동으로 만들어 줘요.
 * TradeService가 사용해요.
 */
package com.sunmo.stockplatform.trade.infrastructure;

import com.sunmo.stockplatform.trade.domain.InvestmentJournal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * 투자 노트를 DB에서 찾아 주는 창구예요.
 * 영수증 번호를 대면 그 영수증에 붙은 메모를 찾아 주는 서류함 담당과 같아요.
 */
public interface InvestmentJournalRepository extends JpaRepository<InvestmentJournal, Long> {
    /** 거래 id로 그 거래의 노트를 찾아요. 받는 것: 거래 id. 돌려주는 것: 노트 또는 빈 값. */
    Optional<InvestmentJournal> findByTradeId(Long tradeId);
}
