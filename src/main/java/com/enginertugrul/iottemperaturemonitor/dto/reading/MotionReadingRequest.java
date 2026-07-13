package com.enginertugrul.iottemperaturemonitor.dto.reading;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MotionReadingRequest(

        @NotBlank
        @Size(max = 256)
        String sensorToken,

        @NotNull
        Boolean motionDetected
) {
}