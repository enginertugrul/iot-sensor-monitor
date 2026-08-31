package com.enginertugrul.iotsensormonitor.support.temperature;

import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;




@Component
public class TemperatureUnitConverter {

    private static final BigDecimal FAHRENHEIT_SCALE = new BigDecimal("1.8");
    private static final BigDecimal FAHRENHEIT_OFFSET = new BigDecimal("32");
    private static final BigDecimal KELVIN_OFFSET = new BigDecimal("273.15");




    public Double convertFromCelsius(Double celsiusValue, TemperatureUnit targetUnit) {
        if (celsiusValue == null) {
            return null;
        }

        return switch (resolve(targetUnit)) {

            case CELSIUS -> celsiusValue;

            case FAHRENHEIT ->
                    celsiusValue * FAHRENHEIT_SCALE.doubleValue()
                            + FAHRENHEIT_OFFSET.doubleValue();

            case KELVIN ->
                    celsiusValue + KELVIN_OFFSET.doubleValue();
        };
    }

    public Double convertToCelsius(Double value, TemperatureUnit sourceUnit) {

        if (value == null) {
            return null;
        }

        return switch (resolve(sourceUnit)) {

            case CELSIUS -> value;

            case FAHRENHEIT -> (value - FAHRENHEIT_OFFSET.doubleValue())
                                        / FAHRENHEIT_SCALE.doubleValue();

            case KELVIN ->
                    value - KELVIN_OFFSET.doubleValue();
        };
    }



    public BigDecimal convertDecimalFromCelsius(BigDecimal celsiusValue, TemperatureUnit targetUnit) {
        if (celsiusValue == null) {
            return null;
        }

        return switch (resolve(targetUnit)) {
            case CELSIUS -> celsiusValue;
            case FAHRENHEIT -> celsiusValue.multiply(FAHRENHEIT_SCALE).add(FAHRENHEIT_OFFSET);
            case KELVIN -> celsiusValue.add(KELVIN_OFFSET);
        };
    }



    public BigDecimal convertSumFromCelsius(BigDecimal celsiusSum, long sampleCount, TemperatureUnit targetUnit) {

        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must not be negative");
        }

        if (celsiusSum == null) {
            return null;
        }

        BigDecimal count = BigDecimal.valueOf(sampleCount);

        return switch (resolve(targetUnit)) {
            case CELSIUS -> celsiusSum;
            case FAHRENHEIT -> celsiusSum.multiply(FAHRENHEIT_SCALE)
                    .add(FAHRENHEIT_OFFSET.multiply(count));
            case KELVIN -> celsiusSum.add(KELVIN_OFFSET.multiply(count));
        };
    }



    public String getSymbol(TemperatureUnit unit) {
        return resolve(unit).getSymbol();
    }



    private TemperatureUnit resolve(TemperatureUnit unit) {
        return unit == null ? TemperatureUnit.CELSIUS : unit;
    }


}
