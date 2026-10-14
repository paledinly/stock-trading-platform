/*
 * 장마감 연구 수집기들이 쓰는 전용 예약 작업 실행기(스케줄러) 3개를 만들어 등록하는 설정 파일이에요.
 * 각 실행기는 스레드 1개라서 같은 실행기의 작업은 겹쳐 돌지 않고, 다른 실행기의 작업과도 서로 막지 않아요.
 * TrajectoryService, TrajectoryContextCollector, 미세구조·수급 수집기가 @Scheduled(scheduler = "...")로 이름을 지정해 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 연구 수집 작업마다 따로 쓰는 작업 실행기를 준비하는 설정 클래스예요.
 * 주방에 요리별 전담 요리사를 한 명씩 두어, 한 요리가 늦어져도 다른 요리 줄이 막히지 않게 하는 것과 같아요.
 */
@Configuration
public class TrajectoryScheduling {
    /** 하는 일: 미세구조(호가 등) 맥락 수집용 단일 스레드 실행기를 만들어요. 받는 것: 없어요. 돌려주는 것: 실행기예요. */
    @Bean("microstructureContextScheduler")
    public ThreadPoolTaskScheduler microstructureScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("closing-microstructure-context-");
        return scheduler;
    }
    /** 하는 일: 1분 집계 저장·스냅샷 계산(flush)용 단일 스레드 실행기를 만들어요. 받는 것: 없어요. 돌려주는 것: 실행기예요. */
    @Bean("trajectoryScheduler")
    public ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("closing-trajectory-");
        return scheduler;
    }
    /** 하는 일: 지수·수급 등 REST 맥락 수집용 단일 스레드 실행기를 만들어요. 받는 것: 없어요. 돌려주는 것: 실행기예요. */
    @Bean("trajectoryContextScheduler")
    public ThreadPoolTaskScheduler contextScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("closing-context-");
        return scheduler;
    }
}
