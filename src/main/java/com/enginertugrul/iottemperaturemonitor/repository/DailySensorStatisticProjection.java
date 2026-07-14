package com.enginertugrul.iottemperaturemonitor.repository;

import java.time.LocalDate;

public interface DailySensorStatisticProjection {

    LocalDate getDate();

    Double getValue();
}