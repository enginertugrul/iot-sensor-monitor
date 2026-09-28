package com.enginertugrul.iotsensormonitor.service.reading.statistics.export;

import com.enginertugrul.iotsensormonitor.dto.statistics.SensorStatisticsExportDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsSensorDTO;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.StatisticsQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.*;
import static com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit.FAHRENHEIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;



@ExtendWith(MockitoExtension.class)
class StatisticsCsvExportServiceTest {

    private static final Long SENSOR_ID = 41L;
    private static final Long OWNER_ID = 7L;
    private static final Instant START = Instant.parse("2026-01-14T00:00:00.123456Z");
    private static final Instant REQUESTED_END = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant EVALUATED_END = Instant.parse("2026-01-14T02:00:00.654321Z");
    private static final String CONTENT = "sensor_name\r\nİç oda\r\n";

    @Mock
    private StatisticsQueryService queryService;

    @Mock
    private StatisticsCsvWriter writer;

    private StatisticsCsvExportService service;

    @BeforeEach
    void setUp() {
        service = new StatisticsCsvExportService(queryService,writer);
    }



    @ParameterizedTest
    @CsvSource({"HOURLY,hourly","DAILY,daily"})
    @ResourceLock(Resources.LOCALE)
    void delegatesTheRequestAndBuildsASafeUtcFilenameFromTheEvaluatedResult(
            StatisticsResolution resolved,String filenameResolution) {
        SensorStatisticsExportDTO export = export(resolved);
        when(queryService.getSummaryExport(SENSOR_ID,OWNER_ID,START,REQUESTED_END,AUTO,FAHRENHEIT))
                .thenReturn(export);
        when(writer.write(export)).thenReturn(CONTENT);

        Locale original = Locale.getDefault();
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            StatisticsCsvExport result = service.createExport(
                    SENSOR_ID,OWNER_ID,START,REQUESTED_END,AUTO,FAHRENHEIT);

            assertThat(result.fileName()).isEqualTo(
                    "sensor-41-statistics-" + filenameResolution
                            + "-20260114T000000Z-to-20260114T020000Z.csv");
            assertThat(result.content()).isEqualTo(CONTENT);

            verify(queryService).getSummaryExport(SENSOR_ID,OWNER_ID,START,REQUESTED_END,AUTO,FAHRENHEIT);
            verify(writer).write(same(export));
            verifyNoMoreInteractions(queryService,writer);
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY,originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT,originalFormat);
        }
    }



    @Test
    void forwardsMissingResolutionAndTemperaturePreferenceToTheQueryService() {
        SensorStatisticsExportDTO export = export(HOURLY);
        when(queryService.getSummaryExport(SENSOR_ID,OWNER_ID,START,REQUESTED_END,null,null))
                .thenReturn(export);
        when(writer.write(export)).thenReturn(CONTENT);

        StatisticsCsvExport result = service.createExport(SENSOR_ID,OWNER_ID,START,REQUESTED_END,null,null);

        assertThat(result.content()).isEqualTo(CONTENT);
        verify(queryService).getSummaryExport(SENSOR_ID,OWNER_ID,START,REQUESTED_END,null,null);
        verify(writer).write(same(export));
    }



    @ParameterizedTest
    @MethodSource("queryFailures")
    void propagatesQueryFailuresWithoutCallingTheWriter(StatisticsResolution resolution,RuntimeException failure) {
        when(queryService.getSummaryExport(SENSOR_ID,OWNER_ID,START,REQUESTED_END,resolution,FAHRENHEIT))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.createExport(
                SENSOR_ID,OWNER_ID,START,REQUESTED_END,resolution,FAHRENHEIT))
                .isSameAs(failure);

        verifyNoInteractions(writer);
    }



    @Test
    void propagatesWriterFailures() {
        SensorStatisticsExportDTO export = export(HOURLY);
        IllegalStateException failure = new IllegalStateException("CSV generation failed");
        when(queryService.getSummaryExport(SENSOR_ID,OWNER_ID,START,REQUESTED_END,AUTO,FAHRENHEIT))
                .thenReturn(export);
        when(writer.write(export)).thenThrow(failure);

        assertThatThrownBy(() -> service.createExport(
                SENSOR_ID,OWNER_ID,START,REQUESTED_END,AUTO,FAHRENHEIT))
                .isSameAs(failure);
    }



    private static Stream<Arguments> queryFailures() {
        return Stream.of(
                Arguments.of(AUTO,new SensorNotFoundException()),
                Arguments.of(RAW,new InvalidStatisticsQueryException("Raw-reading CSV export is not available")),
                Arguments.of(HOURLY,new InvalidStatisticsQueryException(
                        "The requested HOURLY CSV export would contain 4 rows, exceeding the configured limit of 3")));
    }



    private static SensorStatisticsExportDTO export(StatisticsResolution resolution) {
        StatisticsSensorDTO sensor = new StatisticsSensorDTO(
                SENSOR_ID,"../İç \"oda\"",SensorType.TEMPERATURE,"Europe/Istanbul",
                MeasurementUnit.C,"FAHRENHEIT","°F");
        return new SensorStatisticsExportDTO(sensor,START,EVALUATED_END,resolution,List.of());
    }
}