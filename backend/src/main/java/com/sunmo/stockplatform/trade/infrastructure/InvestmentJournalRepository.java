package com.sunmo.stockplatform.trade.infrastructure;

import com.sunmo.stockplatform.trade.domain.InvestmentJournal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface InvestmentJournalRepository extends JpaRepository<InvestmentJournal, Long> {
    Optional<InvestmentJournal> findByTradeId(Long tradeId);
}
