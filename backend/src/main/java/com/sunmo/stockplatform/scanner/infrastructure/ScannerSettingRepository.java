/*
 * 스캐너 설정(ScannerSetting)을 DB에서 찾는 레포지토리(DB 조회 담당) 파일이에요.
 * 메서드 이름 규칙으로 쿼리가 자동으로 만들어져요.
 * 스캐너 조회 서비스, 스캐너 엔진, 기본 설정 부트스트랩, 백테스트가 써요.
 */
package com.sunmo.stockplatform.scanner.infrastructure;

import com.sunmo.stockplatform.scanner.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

/**
 * 스캐너 설정 카드함에서 소유자별로 카드를 찾아 주는 창구예요.
 * 서랍장에서 '내 이름표가 붙은 카드만' 꺼내 주는 것과 같아요.
 */
public interface ScannerSettingRepository extends JpaRepository<ScannerSetting, Long> {
    /**
     * 하는 일: 소유자의 설정 전체를 id 순으로 가져와요.
     * 받는 것: owner.
     * 돌려주는 것: 설정 목록.
     */
    List<ScannerSetting> findByOwnerIdOrderById(Long owner);

    /**
     * 하는 일: 소유자의 활성 설정만 가져와요.
     * 받는 것: owner.
     * 돌려주는 것: 설정 목록.
     */
    List<ScannerSetting> findByOwnerIdAndActiveTrue(Long owner);

    /**
     * 하는 일: id와 소유자가 모두 맞는 설정을 찾아요.
     * 받는 것: id, owner.
     * 돌려주는 것: 있으면 설정, 없으면 빈 Optional.
     */
    Optional<ScannerSetting> findByIdAndOwnerId(Long id, Long owner);

    /**
     * 하는 일: 소유자의 설정 개수를 세요.
     * 받는 것: owner.
     * 돌려주는 것: 개수.
     */
    long countByOwnerId(Long owner);
}
