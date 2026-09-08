package com.enginertugrul.iotsensormonitor.config;

import com.enginertugrul.iotsensormonitor.service.reading.stream.SensorReadingStreamPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class SensorReadingStreamConfig {

    public static final String STREAM_EXECUTOR = "sensorReadingStreamExecutor";

    @Bean(name = STREAM_EXECUTOR)
    public ThreadPoolTaskExecutor sensorReadingStreamExecutor(SensorReadingStreamPolicy policy) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(policy.getWorkerThreads());
        executor.setMaxPoolSize(policy.getWorkerThreads());
        executor.setQueueCapacity(policy.getWorkerQueueCapacity());
        executor.setThreadNamePrefix("sensor-reading-stream-worker-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}