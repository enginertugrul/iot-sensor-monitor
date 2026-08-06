package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;




@Getter
@Component
public class SensorDataLifecyclePolicy {

    private final Duration rawRetention;
    private final Duration hourlyRetention;
    private final Duration dailyRetention;
    private final Duration hourlyRollupInterval;
    private final Duration hourlyRollupGrace;
    private final Duration hourlyRollupTrailingWindow;
    private final Duration dailyRollupInterval;
    private final Duration dailyRollupGrace;
    private final Duration purgeInterval;
    private final int deleteBatchSize;
    private final int maximumBucketsPerRun;
    private final int maximumDeleteBatchesPerRun;



    public SensorDataLifecyclePolicy(
            @Value("${app.sensor-data.lifecycle.raw-retention:P30D}") Duration rawRetention,
            @Value("${app.sensor-data.lifecycle.hourly-retention:P90D}") Duration hourlyRetention,
            @Value("${app.sensor-data.lifecycle.daily-retention:P730D}") Duration dailyRetention,
            @Value("${app.sensor-data.lifecycle.hourly-rollup-interval:PT5M}") Duration hourlyRollupInterval,
            @Value("${app.sensor-data.lifecycle.hourly-rollup-grace:PT5M}") Duration hourlyRollupGrace,
            @Value("${app.sensor-data.lifecycle.hourly-rollup-trailing-window:PT6H}") Duration hourlyRollupTrailingWindow,
            @Value("${app.sensor-data.lifecycle.daily-rollup-interval:PT15M}") Duration dailyRollupInterval,
            @Value("${app.sensor-data.lifecycle.daily-rollup-grace:PT15M}") Duration dailyRollupGrace,
            @Value("${app.sensor-data.lifecycle.purge-interval:PT1H}") Duration purgeInterval,
            @Value("${app.sensor-data.lifecycle.delete-batch-size:1000}") int deleteBatchSize,
            @Value("${app.sensor-data.lifecycle.maximum-buckets-per-run:500}") int maximumBucketsPerRun,
            @Value("${app.sensor-data.lifecycle.maximum-delete-batches-per-run:10}") int maximumDeleteBatchesPerRun
    ) {
        this.rawRetention = requirePositive(rawRetention,"rawRetention");
        this.hourlyRetention = requirePositive(hourlyRetention,"hourlyRetention");
        this.dailyRetention = requirePositive(dailyRetention,"dailyRetention");
        this.hourlyRollupInterval = requirePositive(hourlyRollupInterval,"hourlyRollupInterval");
        this.hourlyRollupGrace = requirePositive(hourlyRollupGrace,"hourlyRollupGrace");
        this.hourlyRollupTrailingWindow = requirePositive(hourlyRollupTrailingWindow,"hourlyRollupTrailingWindow");
        this.dailyRollupInterval = requirePositive(dailyRollupInterval,"dailyRollupInterval");
        this.dailyRollupGrace = requirePositive(dailyRollupGrace,"dailyRollupGrace");
        this.purgeInterval = requirePositive(purgeInterval,"purgeInterval");
        this.deleteBatchSize = requireInRange(deleteBatchSize,1,10_000,"deleteBatchSize");
        this.maximumBucketsPerRun = requireInRange(maximumBucketsPerRun,1,10_000,"maximumBucketsPerRun");
        this.maximumDeleteBatchesPerRun = requireInRange(maximumDeleteBatchesPerRun,1,100,"maximumDeleteBatchesPerRun");

        if (this.rawRetention.compareTo(this.hourlyRetention) >= 0
                || this.hourlyRetention.compareTo(this.dailyRetention) >= 0) {
            throw new IllegalArgumentException("retention periods must satisfy rawRetention < hourlyRetention < dailyRetention");
        }

        if (this.hourlyRollupGrace.compareTo(Duration.ofHours(1)) >= 0) {
            throw new IllegalArgumentException("hourlyRollupGrace must be shorter than one hour");
        }

        if (this.hourlyRollupTrailingWindow.compareTo(Duration.ofHours(1)) < 0
                || this.hourlyRollupTrailingWindow.compareTo(this.rawRetention) > 0) {

            throw new IllegalArgumentException("hourlyRollupTrailingWindow must be at least one hour and no longer than rawRetention");
        }

        if (this.dailyRollupGrace.compareTo(Duration.ofDays(1)) >= 0) {
            throw new IllegalArgumentException(
                    "dailyRollupGrace must be shorter than one day");
        }
    }





    private static Duration requirePositive(Duration value,String fieldName) {
        Duration requiredValue = Objects.requireNonNull(
                value,
                fieldName + " must not be null");

        if (requiredValue.isZero() || requiredValue.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }

        return requiredValue;
    }




    private static int requireInRange(int value,int minimum,int maximum,String fieldName) {

        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(fieldName + " must be between " + minimum + " and " + maximum);
        }

        return value;
    }
}