package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class TrajectoryScheduling {
    @Bean("trajectoryScheduler")
    public ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("closing-trajectory-");
        return scheduler;
    }
    @Bean("trajectoryContextScheduler")
    public ThreadPoolTaskScheduler contextScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("closing-context-");
        return scheduler;
    }
}
