package com.enginertugrul.iotsensormonitor.service.reading.stream;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Getter
@Component
public class SensorReadingStreamPolicy {

    private final Duration refreshInterval;
    private final Duration heartbeatInterval;
    private final Duration connectionLifetime;
    private final Duration workTimeout;
    private final int maximumSubscriptions;
    private final int workerThreads;
    private final int workerQueueCapacity;

    public SensorReadingStreamPolicy(
            @Value("${app.sensor-data.stream.refresh-interval:PT2S}") Duration refreshInterval,
            @Value("${app.sensor-data.stream.heartbeat-interval:PT15S}") Duration heartbeatInterval,
            @Value("${app.sensor-data.stream.connection-lifetime:PT5M}") Duration connectionLifetime,
            @Value("${app.sensor-data.stream.work-timeout:PT15S}") Duration workTimeout,
            @Value("${app.sensor-data.stream.maximum-subscriptions:100}") int maximumSubscriptions,
            @Value("${app.sensor-data.stream.worker-threads:4}") int workerThreads,
            @Value("${app.sensor-data.stream.worker-queue-capacity:100}") int workerQueueCapacity
    ) {
        this.refreshInterval = requireDuration(refreshInterval,Duration.ofSeconds(1),Duration.ofMinutes(1),"refreshInterval");
        this.heartbeatInterval = requireDuration(heartbeatInterval,Duration.ofSeconds(5),Duration.ofMinutes(5),"heartbeatInterval");
        this.connectionLifetime = requireDuration(connectionLifetime,Duration.ofSeconds(30),Duration.ofHours(1),"connectionLifetime");
        this.workTimeout = requireDuration(workTimeout,Duration.ofSeconds(1),Duration.ofMinutes(2),"workTimeout");
        this.maximumSubscriptions = requireInRange(maximumSubscriptions,1,1_000,"maximumSubscriptions");
        this.workerThreads = requireInRange(workerThreads,1,32,"workerThreads");
        this.workerQueueCapacity = requireInRange(workerQueueCapacity,1,2_000,"workerQueueCapacity");

        if (this.heartbeatInterval.compareTo(this.refreshInterval) < 0) {
            throw new IllegalArgumentException("heartbeatInterval must not be shorter than refreshInterval");
        }

        if (this.heartbeatInterval.compareTo(this.connectionLifetime) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than connectionLifetime");
        }

        if (this.workTimeout.compareTo(this.connectionLifetime) >= 0) {
            throw new IllegalArgumentException("workTimeout must be shorter than connectionLifetime");
        }
    }

    private static Duration requireDuration(Duration value,Duration minimum,Duration maximum,String fieldName) {
        Duration requiredValue = Objects.requireNonNull(value,fieldName + " must not be null");

        if (requiredValue.compareTo(minimum) < 0 || requiredValue.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(fieldName + " must be between " + minimum + " and " + maximum);
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