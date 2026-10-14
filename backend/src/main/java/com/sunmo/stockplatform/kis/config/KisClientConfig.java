/*
 * KIS REST 호출에 공통으로 쓸 HTTP 클라이언트(RestClient)를 만드는 스프링 설정 파일이에요.
 * 기본 주소와 연결·읽기 제한시간을 설정값에서 가져와 적용해요.
 * 토큰 발급, 현재가, 분봉, 일봉, 휴장일, 종목 마스터 클라이언트가 이 kisRestClient를 주입받아 써요.
 */
package com.sunmo.stockplatform.kis.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * KIS 전용 HTTP 클라이언트를 하나 만들어 스프링에 등록하는 설정 클래스예요.
 * 회사 공용 전화기에 증권사 대표번호와 "몇 초 안에 안 받으면 끊기"를 미리 설정해 두는 것과 같아요.
 */
@Configuration
public class KisClientConfig {

    /**
     * KIS 기본 주소와 제한시간이 적용된 RestClient(REST 방식 HTTP 요청 도구)를 만들어요.
     * 받는 것: KIS 설정값. 돌려주는 것: 스프링 빈(스프링이 관리하는 공용 객체)으로 등록될 RestClient.
     */
    @Bean
    RestClient kisRestClient(KisProperties properties) {
        var settings = org.springframework.boot.http.client.ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
