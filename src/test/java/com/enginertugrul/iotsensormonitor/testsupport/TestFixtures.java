package com.enginertugrul.iotsensormonitor.testsupport;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;

import java.time.Instant;

public final class TestFixtures {

    public static final Instant CREATED_AT = Instant.parse("2026-01-15T12:00:00Z");
    public static final Instant UPDATED_AT = CREATED_AT.plusSeconds(60);

    private TestFixtures() {}

    public static AppUser user() {
        return new AppUser("owner@example.com","test-password-hash",CREATED_AT);
    }

    public static Sensor sensor(SensorType type) {
        return new Sensor(user(),type,"Living room","Istanbul","Kadikoy","Window","UTC",CREATED_AT);
    }
}