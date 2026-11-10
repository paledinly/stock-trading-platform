/*
 * AI 실시간 감시 전용 작업 실행기 두 개예요. 분 판단·저장(aiWatchScheduler)과 지수 조회(aiWatchMarketScheduler)를 나눠,
 * KIS 응답이 늦어도 1초 판단이 밀리지 않게 하고, 다른 기능의 예약 작업과도 서로 막지 않게 해요.
 */
package com.sunmo.stockplatform.aiplan.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** AI 감시 작업 실행기 설정이에요. 각각 스레드 1개라 같은 작업이 겹쳐 돌지 않아요. */
@Configuration
public class AiWatchScheduling {
    @Bean("aiWatchScheduler")
    public ThreadPoolTaskScheduler aiWatchScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("ai-watch-");
        return scheduler;
    }

    @Bean("aiWatchMarketScheduler")
    public ThreadPoolTaskScheduler aiWatchMarketScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("ai-watch-market-");
        return scheduler;
    }
}
