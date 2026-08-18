package com.sunmo.stockplatform.watchlist.infrastructure;

import com.sunmo.stockplatform.watchlist.domain.WatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {
    List<WatchlistItem> findByGroupIdInOrderByGroupIdAscDisplayOrderAscIdAsc(List<Long> groupIds);
    Optional<WatchlistItem> findByIdAndGroupOwnerId(Long id, Long ownerId);
    boolean existsByGroupIdAndStockId(Long groupId, Long stockId);
    long countByGroupId(Long groupId);
}
