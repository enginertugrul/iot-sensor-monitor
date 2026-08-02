package com.enginertugrul.iotsensormonitor.entity.user;

import lombok.Getter;

@Getter
public enum TemperatureUnit {

    CELSIUS("°C"),
    FAHRENHEIT("°F"),
    KELVIN("K");

    private final String symbol;

    TemperatureUnit(String symbol) {
        this.symbol = symbol;
    }

}
