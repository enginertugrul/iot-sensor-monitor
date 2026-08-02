package com.enginertugrul.iotsensormonitor.support.temperature;

import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import org.springframework.stereotype.Component;





@Component
public class TemperatureUnitConverter {



    public Double convertFromCelsius(Double celsiusValue, TemperatureUnit targetUnit) {
        if (celsiusValue == null) {
            return null;
        }

        return switch (resolve(targetUnit)) {
            case CELSIUS -> celsiusValue;
            case FAHRENHEIT -> (celsiusValue * 9 / 5) + 32;
            case KELVIN -> celsiusValue + 273.15;
        };
    }

    public  Double convertToCelsius(Double value, TemperatureUnit sourceUnit) {
        if (value == null) {
            return null;
        }

        return switch (resolve(sourceUnit)) {
            case CELSIUS -> value;
            case FAHRENHEIT -> (value - 32) * 5 / 9;
            case KELVIN -> value - 273.15;
        };
    }

    public String getSymbol(TemperatureUnit unit) {
        return resolve(unit).getSymbol();
    }

    private TemperatureUnit resolve(TemperatureUnit unit) {
        return unit == null ? TemperatureUnit.CELSIUS : unit;
    }


}
