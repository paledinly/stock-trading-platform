package com.sunmo.stockplatform.scanner.application;

import com.sunmo.stockplatform.scanner.domain.*;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerSettingRepository;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ScannerSettingBootstrap implements ApplicationRunner {
    private final ScannerSettingRepository repository;

    public ScannerSettingBootstrap(ScannerSettingRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> existing = repository.findByOwnerIdOrderById(1L).stream()
                .map(ScannerSetting::getName)
                .collect(Collectors.toSet());
        saveMissing(existing, setting("거래량 급증", ScannerType.VOLUME, z(), bd("2.0")));
        saveMissing(existing, setting("5분 급등", ScannerType.PRICE_RISE, bd("2.0"), z()));
        saveMissing(existing, setting("Momentum", ScannerType.MOMENTUM, bd("2.0"), bd("2.0")));
        saveMissing(existing, setting("Volume Breakout", ScannerType.VOLUME_BREAKOUT, z(), bd("2.5")));
        saveMissing(existing, setting("Turnover Breakout", ScannerType.TURNOVER_BREAKOUT, z(), bd("2.0")));
        saveMissing(existing, setting("High Breakout", ScannerType.HIGH_BREAKOUT, bd("1.0"), z()));
        saveMissing(existing, setting("VWAP Breakout", ScannerType.VWAP_BREAKOUT, bd("1.0"), z()));
        saveMissing(existing, setting("VWAP Reclaim", ScannerType.VWAP_RECLAIM, bd("0.5"), z()));
        saveMissing(existing, setting("Pullback Rebreak", ScannerType.PULLBACK_REBREAK, z(), z()));
    }

    private void saveMissing(Set<String> existing, ScannerSetting setting) {
        if (existing.add(setting.getName()))
            repository.save(setting);
    }

    private ScannerSetting setting(String name, ScannerType type, BigDecimal change, BigDecimal ratio) {
        return new ScannerSetting(1L, name, type, change, ratio, z(), z(), z(), false, 300);
    }

    private BigDecimal z() {
        return BigDecimal.ZERO;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
