package com.enginertugrul.iotsensormonitor.config;

import com.enginertugrul.iotsensormonitor.scheduler.SensorReadingStreamScheduler;
import com.enginertugrul.iotsensormonitor.service.reading.stream.SensorReadingStreamService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling",name = "enabled",havingValue = "true",matchIfMissing = true)
public class SchedulingConfig {

    @Bean
    public SensorReadingStreamScheduler sensorReadingStreamScheduler(SensorReadingStreamService streamService) {
        return new SensorReadingStreamScheduler(streamService);
    }
}