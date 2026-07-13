package com.enginertugrul.iottemperaturemonitor.entity.reading;

import lombok.Getter;

@Getter
public enum MeasurementUnit {

    CELSIUS("C"),
    PERCENT("PERCENT");

    private final String databaseValue;

    MeasurementUnit(String databaseValue) {
        this.databaseValue = databaseValue;
    }

}