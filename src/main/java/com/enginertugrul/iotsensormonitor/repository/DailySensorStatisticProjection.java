package com.enginertugrul.iotsensormonitor.repository;

import java.time.LocalDate;

public interface DailySensorStatisticProjection {

    LocalDate getDate();

    Double getValue();
}