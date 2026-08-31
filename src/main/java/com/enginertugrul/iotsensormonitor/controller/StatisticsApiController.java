package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.dto.statistics.SensorStatisticsSeriesDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.StatisticsQueryService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;



@RestController
@RequestMapping("/api/sensors/{sensorId}/statistics")
public class StatisticsApiController {


    private final StatisticsQueryService statisticsQueryService;
    private final AppUserService appUserService;


    public StatisticsApiController(StatisticsQueryService statisticsQueryService, AppUserService appUserService) {
        this.statisticsQueryService = statisticsQueryService;
        this.appUserService = appUserService;
    }





    @GetMapping("/series")
    public SensorStatisticsSeriesDTO getSeries(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable Long sensorId,
            @RequestParam("startInclusive")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant startInclusive,
            @RequestParam("endExclusive")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant endExclusive,
            @RequestParam(value = "resolution",defaultValue = "AUTO")
            StatisticsResolution requestedResolution
    ) {

        Long ownerId = authenticatedUser.getAppUserId();
        TemperatureUnit temperatureUnit = appUserService.getPreferredTemperatureUnit(ownerId);

        return statisticsQueryService.getSeries(
                sensorId,
                ownerId,
                startInclusive,
                endExclusive,
                requestedResolution,
                temperatureUnit);
    }
}