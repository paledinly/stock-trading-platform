/*
 * 미세구조(호가·큰 체결) 연구 수집의 설정을 closing.trajectory.microstructure에서 읽어 와요.
 * 기본 꺼짐이고, 호가 조회는 최소 5초 간격·최대 5종목으로 제한해요. 큰 체결 기준은 기본 1억 원이에요.
 * MicrostructureService, MicrostructureAggregator, OrderbookCollector가 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * 미세구조 수집의 정책(켜기/끄기, 호가 조회 간격·종목 수, 고정 종목 목록, 큰 체결 기준 금액, 관측 간격 허용치, 늦은 자료 대기시간)을 담아요.
 * 망원경 관측 계획표처럼 "몇 초마다, 몇 개 별까지, 얼마나 끊겨도 괜찮은지"를 정하고 무리한 계획은 거부해요.
 */
@ConfigurationProperties("closing.trajectory.microstructure")
public record MicrostructureProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean orderbookEnabled,
        @DefaultValue("5s") Duration pollInterval, @DefaultValue("2") int maxSymbols,
        List<String> symbols, @DefaultValue("100000000") BigDecimal largeTradeThreshold,
        @DefaultValue("15s") Duration maxObservationGap, @DefaultValue("2s") Duration watermark) {
    /**
     * 하는 일: 종목 목록을 복사본으로 만들고 범위를 검사해요(간격 5초 이상, 종목 1~5개, 6자리 종목코드, 관측 간격 5~30초, 대기시간 0~10초).
     * 받는 것: 설정 값이에요. 돌려주는 것: 검증된 설정이에요. 범위를 벗어나면 예외를 던져요.
     */
    public MicrostructureProperties {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        if (pollInterval.compareTo(Duration.ofSeconds(5)) < 0 || maxSymbols < 1 || maxSymbols > 5
                || symbols.size() > maxSymbols || symbols.stream().anyMatch(s -> !s.matches("[0-9]{6}"))
                || largeTradeThreshold.signum() <= 0 || maxObservationGap.compareTo(Duration.ofSeconds(5)) < 0
                || maxObservationGap.compareTo(Duration.ofSeconds(30)) > 0
                || watermark.isNegative() || watermark.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("Invalid microstructure observation policy");
    }
}
