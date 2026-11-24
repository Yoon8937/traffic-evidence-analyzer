package com.backend.config;

import com.backend.analysis.AnalysisSchedulerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableConfigurationProperties(AnalysisSchedulerProperties.class)
public class AnalysisSchedulerConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "app.analysis.scheduler", name = "enabled", havingValue = "true")
    static class SchedulingEnabledConfig implements SchedulingConfigurer {

        @Override
        public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(3);
            scheduler.setThreadNamePrefix("analysis-scheduler-");
            scheduler.setWaitForTasksToCompleteOnShutdown(true);
            scheduler.setAwaitTerminationSeconds(10);
            scheduler.initialize();
            taskRegistrar.setTaskScheduler(scheduler);
        }
    }
}
