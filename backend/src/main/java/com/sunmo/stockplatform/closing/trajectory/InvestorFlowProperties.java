/*
 * 수급(외국인·기관·프로그램 매매 동향) 연구 수집의 설정을 closing.trajectory.flow에서 읽어 와요.
 * 기본 꺼짐이고, 켜면 최대 maxSymbols개 종목을 interval 간격으로 조회해요.
 * InvestorFlowCollector가 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/**
 * 수급 수집의 예산(켜기/끄기, 종목 수 상한, 조회 간격)을 담는 설정이에요.
 * 휴대폰 데이터 요금제처럼 "몇 종목까지, 몇 분마다"라는 사용 한도를 정해 API 호출이 과하지 않게 막아요.
 */
@ConfigurationProperties("closing.trajectory.flow")
public record InvestorFlowProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("20") int maxSymbols, @DefaultValue("5m") Duration interval) {
    /** 하는 일: 종목 수가 1~40이고 간격이 1분 이상인지 검사해요. 받는 것: 설정 값. 돌려주는 것: 검증된 설정이에요(아니면 예외). */
    public InvestorFlowProperties {
        if (maxSymbols < 1 || maxSymbols > 40 || interval.compareTo(Duration.ofMinutes(1)) < 0)
            throw new IllegalArgumentException("Invalid investor flow budget");
    }
}
