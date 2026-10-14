/*
 * 스케줄러(정해진 주기·시각에 작업을 자동 실행하는 장치) 스레드 풀 두 개를 만들어요.
 * 하나는 스프링 기본 @Scheduled 작업용(taskScheduler), 하나는 Broad 보강 큐 전용(broadEnrichmentScheduler)이에요.
 * scheduler 이름을 따로 지정하지 않은 모든 @Scheduled 메서드가 taskScheduler를 쓰고, BroadEnrichmentQueue는 전용 스케줄러를 써요.
 */
package com.sunmo.stockplatform.market.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 자동 작업 담당자를 정하는 설정이에요.
 * 회사에서 일반 잡무 담당 1명과 보강 작업 전담 1명을 따로 두는 것처럼, 보강 큐가 다른 정기 작업에 밀리거나 밀어내지 않게 분리해요.
 */
@Configuration
public class BroadEnrichmentScheduling {
    /**
     * 하는 일: 기본 스케줄러를 스레드 1개로 만들어요.
     * 돌려주는 것: "market-scheduled-" 이름의 스레드를 쓰는 스케줄러.
     * ⚠️ 확인 필요: 스레드가 1개라서 이름 없는 @Scheduled 작업(봉 확정 closeExpired 1초 주기, 14:25/14:35 일봉 보완 등)이 모두 한 줄로 실행돼요.
     * 오래 걸리는 작업이 돌면 장중 봉 확정(특히 다음 틱이 없는 15:25 봉)이 그만큼 늦어질 수 있어요.
     */
    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("market-scheduled-");
        return scheduler;
    }

    /**
     * 하는 일: Broad 보강 큐 전용 스케줄러를 스레드 1개로 만들어요. 종료할 때 남은 작업을 기다리지 않아요.
     * 돌려주는 것: "broad-enrichment-" 이름의 스레드를 쓰는 스케줄러.
     */
    @Bean("broadEnrichmentScheduler")
    public ThreadPoolTaskScheduler broadEnrichmentScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("broad-enrichment-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
