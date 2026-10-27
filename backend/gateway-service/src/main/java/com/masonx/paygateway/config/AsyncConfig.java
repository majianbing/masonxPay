package com.masonx.paygateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executor;

/**
 * Named async executors run one virtual thread per task (JDK 25 + {@code spring.threads.virtual.enabled}).
 * The concurrency limit replaces the old platform-thread pool bounds: it caps how many
 * gateway_logs inserts can be in flight so a traffic burst queues on the caller instead of
 * flooding the Hikari pool. Boot's own virtual-thread {@code applicationTaskExecutor} still
 * serves unqualified {@code @Async} and MVC async because {@code spring.task.execution.mode=force}.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "gatewayLogExecutor")
    public Executor gatewayLogExecutor(
            @Value("${app.gateway-logs.direct-insert-concurrency-limit:32}") int concurrencyLimit) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("gateway-log-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(concurrencyLimit);
        return executor;
    }
}
