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

    @org.springframework.data.jpa.repository.Query("select distinct i.stock.stockCode from WatchlistItem i where i.group.ownerId = :ownerId")
    List<String> findDistinctStockCodesByOwnerId(
            @org.springframework.data.repository.query.Param("ownerId") Long ownerId);

    long countByStockId(Long stockId);
}
