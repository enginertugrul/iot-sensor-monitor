package com.enginertugrul.iotsensormonitor.dto.reading;

import com.enginertugrul.iotsensormonitor.validation.IsFiniteDouble;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;




public record HumidityReadingRequest(

        @NotBlank
        @Size(max = 256)
        String sensorToken,

        @NotNull
        @IsFiniteDouble
        @DecimalMin(value = "0.0", message = "humidityPercentage must be at least 0")
        @DecimalMax(value = "100.0", message = "humidityPercentage must not exceed 100")
        Double humidityPercentage
) {
}