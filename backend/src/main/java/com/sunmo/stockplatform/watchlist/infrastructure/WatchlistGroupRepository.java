package com.sunmo.stockplatform.watchlist.infrastructure;

import com.sunmo.stockplatform.watchlist.domain.WatchlistGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WatchlistGroupRepository extends JpaRepository<WatchlistGroup, Long> {
    List<WatchlistGroup> findByOwnerIdOrderByDisplayOrderAscIdAsc(Long ownerId);

    Optional<WatchlistGroup> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByOwnerIdAndName(Long ownerId, String name);
}
