package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;




@Component
public class DataRetentionScheduler {


    private final SensorReadingRepository sensorReadingRepository;



    public DataRetentionScheduler(SensorReadingRepository sensorReadingRepository) {
        this.sensorReadingRepository = sensorReadingRepository;
    }



    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeOldSensorData() {

        short daysToKeepSensorData = 14;
        Instant cutoffDate = Instant.now().minus(daysToKeepSensorData, ChronoUnit.DAYS);

        sensorReadingRepository.deleteOlderThan(cutoffDate);

    }


}
