package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.service.reading.stream.SensorReadingStreamService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;



@Component
public class SensorReadingStreamScheduler {

    private final Logger logger = LoggerFactory.getLogger(SensorReadingStreamScheduler.class);
    private final SensorReadingStreamService streamService;
    private final ScheduledThreadPoolExecutor timer;

    public SensorReadingStreamScheduler(SensorReadingStreamService streamService) {
        this.streamService = streamService;
        this.timer = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon(true).name("sensor-reading-stream-timer-",0).factory());

        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @PostConstruct
    public void start() {
        timer.scheduleWithFixedDelay(this::runMaintenance,1,1,TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        timer.shutdownNow();
    }

    private void runMaintenance() {
        try {
            streamService.maintainStreams();
        } catch (RuntimeException exception) {
            logger.error("Recent reading stream maintenance failed. failureType={}",
                    exception.getClass().getSimpleName(),exception);
        }
    }
}