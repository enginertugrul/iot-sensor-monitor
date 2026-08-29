package com.enginertugrul.iotsensormonitor.service.reading.statistics.export;

import com.enginertugrul.iotsensormonitor.dto.statistics.SensorStatisticsExportDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.StatisticsQueryService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class StatisticsCsvExportService {

    private static final DateTimeFormatter FILE_TIME_FORMATTER =
            DateTimeFormatter
                    .ofPattern(
                            "uuuuMMdd'T'HHmmss'Z'",
                            Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private final StatisticsQueryService statisticsQueryService;
    private final StatisticsCsvWriter statisticsCsvWriter;

    public StatisticsCsvExportService(
            StatisticsQueryService statisticsQueryService,
            StatisticsCsvWriter statisticsCsvWriter
    ) {
        this.statisticsQueryService = statisticsQueryService;
        this.statisticsCsvWriter = statisticsCsvWriter;
    }

    public StatisticsCsvExport createExport(
            Long sensorId,
            Long ownerId,
            Instant startInclusive,
            Instant endExclusive,
            StatisticsResolution resolution,
            TemperatureUnit temperatureUnit
    ) {
        SensorStatisticsExportDTO export =
                statisticsQueryService.getSummaryExport(
                        sensorId,
                        ownerId,
                        startInclusive,
                        endExclusive,
                        resolution,
                        temperatureUnit);

        String fileName = "sensor-"
                + export.sensor().id()
                + "-statistics-"
                + export.resolvedResolution()
                .name()
                .toLowerCase(Locale.ROOT)
                + "-"
                + FILE_TIME_FORMATTER.format(
                export.evaluatedStartInclusive())
                + "-to-"
                + FILE_TIME_FORMATTER.format(
                export.evaluatedEndExclusive())
                + ".csv";

        return new StatisticsCsvExport(
                fileName,
                statisticsCsvWriter.write(export));
    }
}