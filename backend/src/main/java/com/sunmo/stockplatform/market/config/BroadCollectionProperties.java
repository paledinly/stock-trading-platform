/*
 * 시장 전체(Broad) 후보 수집에 쓰는 설정값(market.wide.collection.*)을 담아요.
 * 빈 값에는 기본값을 채우고, 허용 범위를 벗어나면 서버 시작 시 바로 실패시켜요.
 * marketwide 패키지의 Broad 후보 수집기가 KIS 순위 조회 개수와 상세 현재가 조회 한도를 정할 때 사용해요.
 */
package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * Broad 수집 설정 묶음이에요. 장보기 규칙 메모처럼 "순위표는 몇 개까지 볼지, 상세 조회는 몇 번까지 할지, 얼마나 오래된 값까지 믿을지"를 적어 둬요.
 * rankingLimit: KIS 순위 조회 개수(기본 100, 최대 100), detailQuoteBudget: 상세 현재가 조회 한도(0~120),
 * cacheMaxAge: 캐시(한 번 받은 결과를 잠시 보관해 재사용하는 것) 유효 시간(기본 60초), tickMaxAge: 실시간 틱을 최신으로 볼 최대 나이(기본 30초),
 * excludeStructuredNames: 종목명으로 구조화 상품(StructuredProductFilter 기준)을 거를지 여부예요.
 */
@ConfigurationProperties(prefix = "market.wide.collection")
public record BroadCollectionProperties(int rankingLimit, int detailQuoteBudget, Duration cacheMaxAge,
        Duration tickMaxAge, boolean excludeStructuredNames) {
    /** 하는 일: 빈 값에 기본값을 채우고 범위를 검사해요. 잘못된 설정이면 IllegalArgumentException을 던져요. */
    public BroadCollectionProperties {
        rankingLimit = rankingLimit <= 0 ? 100 : rankingLimit;
        cacheMaxAge = cacheMaxAge == null ? Duration.ofSeconds(60) : cacheMaxAge;
        tickMaxAge = tickMaxAge == null ? Duration.ofSeconds(30) : tickMaxAge;
        if (rankingLimit > 100 || detailQuoteBudget < 0 || detailQuoteBudget > 120
                || cacheMaxAge.isNegative() || tickMaxAge.isNegative())
            throw new IllegalArgumentException("Invalid Broad collection configuration");
    }
}
