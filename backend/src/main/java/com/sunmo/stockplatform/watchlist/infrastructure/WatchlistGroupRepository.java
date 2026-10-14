/*
 * 관심종목 그룹을 DB에서 찾고 저장하는 레포지토리(DB 읽기/쓰기를 맡는 창구)예요.
 * Spring Data JPA가 메서드 이름을 읽고 SQL을 자동으로 만들어 줘요.
 * WatchlistService와 InitialWatchlistBootstrap이 사용해요.
 */
package com.sunmo.stockplatform.watchlist.infrastructure;

import com.sunmo.stockplatform.watchlist.domain.WatchlistGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * watchlist_group 테이블용 레포지토리예요.
 * 서류 보관실 직원처럼 "이 사람 서랍들 순서대로 줘", "이 번호 서랍 있어?" 같은 요청을 처리해요.
 * JPA(자바 객체와 DB 테이블을 자동으로 연결해 주는 표준 기술)를 써요.
 */
public interface WatchlistGroupRepository extends JpaRepository<WatchlistGroup, Long> {
    /** 소유자의 그룹을 표시 순서, 같으면 id 순으로 돌려줘요. */
    List<WatchlistGroup> findByOwnerIdOrderByDisplayOrderAscIdAsc(Long ownerId);

    /** id와 소유자가 모두 맞는 그룹을 찾아요. 남의 그룹은 찾지 못하게 해요. */
    Optional<WatchlistGroup> findByIdAndOwnerId(Long id, Long ownerId);

    /** 소유자에게 같은 이름의 그룹이 이미 있는지 확인해요. */
    boolean existsByOwnerIdAndName(Long ownerId, String name);
}
