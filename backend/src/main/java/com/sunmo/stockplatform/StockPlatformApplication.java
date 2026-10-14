/*
 * 백엔드 서버 전체를 켜는 시작점(main)이에요.
 * 스프링 부트 설정 자동 구성, 설정값(application.yml) 클래스 자동 등록, 정해진 시각 작업(스케줄러)을 함께 켜요.
 * 서버를 실행할 때(gradlew bootRun, 배포된 jar 실행) 가장 먼저 호출돼요.
 */
package com.sunmo.stockplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주식 연구 플랫폼 백엔드 애플리케이션 클래스예요.
 * 가게 문을 열면서 전등, 계산대, 타이머를 한꺼번에 켜는 "개점 스위치"라고 생각하면 돼요.
 * {@code @ConfigurationPropertiesScan}은 설정값 묶음 클래스를 자동으로 찾아 등록하고,
 * {@code @EnableScheduling}은 스케줄러(정해진 시각이나 간격마다 작업을 자동 실행하는 장치)를 켜요.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class StockPlatformApplication {

    /**
     * 스프링 애플리케이션을 실행해요.
     * 받는 것: 명령줄 인자(args). 돌려주는 것: 없음(서버가 종료될 때까지 계속 동작해요).
     */
    public static void main(String[] args) {
        SpringApplication.run(StockPlatformApplication.class, args);
    }
}
