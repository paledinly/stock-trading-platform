/*
 * 서버가 켜질 때 기본 스캐너 설정 9가지 중 없는 것을 만들어 넣는 파일이에요.
 * 같은 이름의 설정이 이미 있으면 건드리지 않아요.
 * 스프링이 애플리케이션 시작 직후 자동으로 실행해요.
 */
package com.sunmo.stockplatform.scanner.application;

import com.sunmo.stockplatform.scanner.domain.*;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerSettingRepository;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 기본 스캐너 설정을 채워 넣는 시작 도우미 클래스예요.
 * 새 가게를 열 때 진열대에 기본 상품이 비어 있으면 채워 두고, 이미 있으면 그대로 두는 직원과 같아요.
 */
@Component
public class ScannerSettingBootstrap implements ApplicationRunner {
    private final ScannerSettingRepository repository;

    /**
     * 하는 일: 설정 레포지토리(DB 조회 담당)를 받아 보관해요.
     * 받는 것: repository.
     * 돌려주는 것: 없음(생성자).
     */
    public ScannerSettingBootstrap(ScannerSettingRepository repository) {
        this.repository = repository;
    }

    /**
     * 하는 일: 기존 설정 이름을 모으고, 빠진 기본 설정만 저장해요.
     * 받는 것: args(시작 인자, 쓰지 않아요).
     * 돌려주는 것: 없음.
     * 이름으로만 비교해서, 사용자가 기본 설정 이름을 바꾸면 다음 시작 때 기본 설정이 다시 만들어지는 것으로 보여요.
     */
    @Override
    public void run(ApplicationArguments args) {
        Set<String> existing = repository.findByOwnerIdOrderById(1L).stream()
                .map(ScannerSetting::getName)
                .collect(Collectors.toSet());
        // 기본값 순서: (이름, 종류, 최소 변화율 %, 최소 거래량 배수)예요.
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

    /**
     * 하는 일: 이름이 아직 없으면 저장하고 이름 집합에 추가해요.
     * 받는 것: existing(이미 있는 이름들), setting(저장할 기본 설정).
     * 돌려주는 것: 없음.
     */
    private void saveMissing(Set<String> existing, ScannerSetting setting) {
        if (existing.add(setting.getName()))
            repository.save(setting);
    }

    /**
     * 하는 일: 기본 설정 객체를 만들어요.
     * 받는 것: name, type, change(최소 변화율 %), ratio(최소 거래량 배수).
     * 돌려주는 것: 거래대금·최소 가격 0, ETF 제외, 쿨다운(다시 알리기 전 쉬는 시간) 300초인 설정.
     */
    private ScannerSetting setting(String name, ScannerType type, BigDecimal change, BigDecimal ratio) {
        return new ScannerSetting(1L, name, type, change, ratio, z(), z(), z(), false, 300);
    }

    /**
     * z는 0을, bd는 문자열을 BigDecimal(정확한 소수 숫자)로 바꿔 돌려주는 짧은 도우미예요.
     */
    private BigDecimal z() {
        return BigDecimal.ZERO;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
