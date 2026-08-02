package com.enginertugrul.iotsensormonitor.dto.reading;

import com.enginertugrul.iotsensormonitor.validation.IsFiniteDouble;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;




public record TemperatureReadingRequest(

        @NotBlank
        @Size(max = 256)
        String sensorToken,

        @NotNull
        @IsFiniteDouble
        @DecimalMin(value = "-273.15" , message = "celsiusValue must be at least -273.15 (Absolute zero) ")
        Double celsiusValue
) {
}