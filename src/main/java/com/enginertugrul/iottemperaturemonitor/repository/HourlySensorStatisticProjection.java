package com.enginertugrul.iottemperaturemonitor.repository;

public interface HourlySensorStatisticProjection {

    Short getHour();

    Double getValue();
}