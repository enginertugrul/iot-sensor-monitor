package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.export.StatisticsCsvExport;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.export.StatisticsCsvExportService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;





@RestController
@RequestMapping("/api/sensors/{sensorId}/statistics")
public class StatisticsExportController {

    private static final MediaType CSV_MEDIA_TYPE = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final StatisticsCsvExportService statisticsCsvExportService;
    private final AppUserService appUserService;



    public StatisticsExportController(StatisticsCsvExportService statisticsCsvExportService, AppUserService appUserService) {
        this.statisticsCsvExportService = statisticsCsvExportService;
        this.appUserService = appUserService;
    }



    @GetMapping(value = "/export.csv",produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable Long sensorId,
            @RequestParam("startInclusive")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant startInclusive,
            @RequestParam("endExclusive")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant endExclusive,
            @RequestParam(value = "resolution",defaultValue = "AUTO")
            StatisticsResolution resolution
    ) {

        Long ownerId = authenticatedUser.getAppUserId();
        TemperatureUnit temperatureUnit =
                appUserService.getPreferredTemperatureUnit(ownerId);

        StatisticsCsvExport export = statisticsCsvExportService.createExport(
                sensorId,
                ownerId,
                startInclusive,
                endExclusive,
                resolution,
                temperatureUnit);

        byte[] body = export.content().getBytes(StandardCharsets.UTF_8);

        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(export.fileName(),StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(CSV_MEDIA_TYPE)
                .contentLength(body.length)
                .cacheControl(CacheControl.noStore())
                .headers(headers -> headers.setContentDisposition(contentDisposition))
                .header("X-Content-Type-Options","nosniff")
                .body(body);
    }
}