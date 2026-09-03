package com.sunmo.stockplatform.scanner.infrastructure;

import com.sunmo.stockplatform.scanner.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ScannerSettingRepository extends JpaRepository<ScannerSetting, Long> {
    List<ScannerSetting> findByOwnerIdOrderById(Long owner);

    List<ScannerSetting> findByOwnerIdAndActiveTrue(Long owner);

    Optional<ScannerSetting> findByIdAndOwnerId(Long id, Long owner);

    long countByOwnerId(Long owner);
}
