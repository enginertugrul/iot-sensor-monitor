package com.enginertugrul.iotsensormonitor.dto.reading;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;


public record MotionReadingRequest(

        @NotBlank
        @Size(max = 256)
        String sensorToken,

        @NotNull
        Boolean motionDetected,

        @NotNull
        Instant recordedAt
) {
}