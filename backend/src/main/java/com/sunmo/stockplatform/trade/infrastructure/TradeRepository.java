package com.sunmo.stockplatform.trade.infrastructure;
import com.sunmo.stockplatform.trade.domain.Trade;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface TradeRepository extends JpaRepository<Trade,Long>{
 List<Trade> findByOwnerIdOrderByTradedAtDescIdDesc(Long ownerId,Pageable pageable);
 List<Trade> findByOwnerIdOrderByTradedAtAscIdAsc(Long ownerId);
 Optional<Trade> findByIdAndOwnerId(Long id,Long ownerId);
 Optional<Trade> findByOwnerIdAndIdempotencyKey(Long ownerId,String key);
}
