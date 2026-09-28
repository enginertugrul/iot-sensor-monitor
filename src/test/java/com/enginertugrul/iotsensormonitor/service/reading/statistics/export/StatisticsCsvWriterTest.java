package com.enginertugrul.iotsensormonitor.service.reading.statistics.export;

import com.enginertugrul.iotsensormonitor.dto.statistics.*;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.*;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.*;
import static com.enginertugrul.iotsensormonitor.entity.sensor.SensorType.*;
import static org.assertj.core.api.Assertions.assertThat;



class StatisticsCsvWriterTest {

    private static final Instant START = Instant.parse("2026-01-14T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private static final Instant FINALIZED_AT = END.plusSeconds(60);
    private static final Instant REFRESHED_AT = END.plusSeconds(120);


    private static final String COMMON_HEADER =
            "granularity,sensor_id,sensor_name,sensor_type,sensor_time_zone,period_time_zone,"
                    + "local_date,period_start_utc,period_end_utc,status";


    private static final String NUMERIC_HEADER = COMMON_HEADER
            + ",sample_count,canonical_storage_unit,metric_unit,metric_unit_symbol,"
            + "sum,minimum,average,maximum,finalized_at_utc,refreshed_at_utc\r\n";


    private static final String MOTION_HEADER = COMMON_HEADER
            + ",total_sample_count,true_sample_count,false_sample_count,true_percentage,"
            + "finalized_at_utc,refreshed_at_utc\r\n";


    private final StatisticsCsvWriter writer = new StatisticsCsvWriter();



    @Test
    void writesTemperatureColumnsNegativeNumbersAndFinalizationTimestamps() {
        StatisticsSeriesPointDTO point = hour(START,COMPLETE,2L,
                numeric("30.000","-10.500","15.000","40.500"),null,FINALIZED_AT,REFRESHED_AT);

        String result = write(sensor(TEMPERATURE,"Temperature"),HOURLY,point);

        assertThat(result).isEqualTo(NUMERIC_HEADER + line(
                "HOURLY","41","Temperature","TEMPERATURE","Europe/Istanbul","UTC","",
                START.toString(),END.toString(),"COMPLETE",
                "2","C","CELSIUS","°C","30","-10.5","15","40.5",
                FINALIZED_AT.toString(),REFRESHED_AT.toString()));
    }



    @Test
    void writesHumidityWithItsCanonicalAndDisplayUnits() {
        StatisticsSeriesPointDTO point = hour(START,COMPLETE,2L,
                numeric("100.00","40.00","50.00","60.00"),null,null,null);

        String result = write(sensor(HUMIDITY,"Humidity"),HOURLY,point);

        assertThat(result).isEqualTo(NUMERIC_HEADER + line(
                "HOURLY","41","Humidity","HUMIDITY","Europe/Istanbul","UTC","",
                START.toString(),END.toString(),"COMPLETE",
                "2","PERCENT","PERCENT","% RH","100","40","50","60","",""));
    }



    @Test
    void writesMotionCountsAndPercentageUsingTheMotionSchema() {
        StatisticsMotionMetricsDTO metrics = new StatisticsMotionMetricsDTO(4,1,3,new BigDecimal("25.000"));
        StatisticsSeriesPointDTO point = hour(START,COMPLETE,4L,null,metrics,FINALIZED_AT,REFRESHED_AT);

        String result = write(sensor(MOTION,"Motion"),HOURLY,point);

        assertThat(result).isEqualTo(MOTION_HEADER + line(
                "HOURLY","41","Motion","MOTION","Europe/Istanbul","UTC","",
                START.toString(),END.toString(),"COMPLETE",
                "4","1","3","25",FINALIZED_AT.toString(),REFRESHED_AT.toString()));
    }



    @Test
    void writesDailyLocalDateTimezoneAndActualUtcBoundaries() {
        LocalDate date = LocalDate.of(2025,3,30);
        Instant start = Instant.parse("2025-03-29T23:00:00Z");
        Instant end = Instant.parse("2025-03-30T22:00:00Z");
        StatisticsSensorDTO sensor = new StatisticsSensorDTO(
                41L,"Berlin",TEMPERATURE,"Europe/Berlin",MeasurementUnit.C,"CELSIUS","°C");
        StatisticsSeriesPointDTO point = new StatisticsSeriesPointDTO(
                StatisticsDisplayGranularity.DAILY,null,null,start,end,date,date.plusDays(1),
                "Europe/Berlin",COMPLETE,1L,numeric("20","20","20","20"),null,null,null);

        String result = write(sensor,DAILY,point);

        assertThat(result).isEqualTo(NUMERIC_HEADER + line(
                "DAILY","41","Berlin","TEMPERATURE","Europe/Berlin","Europe/Berlin","2025-03-30",
                "2025-03-29T23:00:00Z","2025-03-30T22:00:00Z","COMPLETE",
                "1","C","CELSIUS","°C","20","20","20","20","",""));
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void writesOnlyTheAppropriateHeaderWhenThereAreNoRows(SensorType type) {
        String result = write(sensor(type,"Sensor"),HOURLY);

        assertThat(result).isEqualTo(type == MOTION ? MOTION_HEADER : NUMERIC_HEADER);
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsPointStatus.class,names = {"EXPIRED","ROLLUP_DELAY"})
    void distinguishesUnavailableNumericMetricsFromKnownEmptyBuckets(StatisticsPointStatus status) {
        StatisticsSeriesPointDTO unavailable = hour(START,status,null,null,null,null,null);
        StatisticsSeriesPointDTO empty = hour(END,NO_SAMPLES,0L,null,null,null,null);

        String result = write(sensor(TEMPERATURE,"Temperature"),HOURLY,unavailable,empty);

        assertThat(result).isEqualTo(NUMERIC_HEADER
                + line("HOURLY","41","Temperature","TEMPERATURE","Europe/Istanbul","UTC","",
                START.toString(),END.toString(),status.name(),
                "","C","CELSIUS","°C","","","","","","")
                + line("HOURLY","41","Temperature","TEMPERATURE","Europe/Istanbul","UTC","",
                END.toString(),END.plusSeconds(3600).toString(),"NO_SAMPLES",
                "0","C","CELSIUS","°C","","","","","",""));
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsPointStatus.class,names = {"EXPIRED","ROLLUP_DELAY"})
    void distinguishesUnavailableMotionMetricsFromZeroCounts(StatisticsPointStatus status) {
        StatisticsSeriesPointDTO unavailable = hour(START,status,null,null,null,null,null);
        StatisticsMotionMetricsDTO emptyMetrics = new StatisticsMotionMetricsDTO(0,0,0,null);
        StatisticsSeriesPointDTO empty = hour(END,NO_SAMPLES,0L,null,emptyMetrics,null,null);

        String result = write(sensor(MOTION,"Motion"),HOURLY,unavailable,empty);

        assertThat(result).isEqualTo(MOTION_HEADER
                + line("HOURLY","41","Motion","MOTION","Europe/Istanbul","UTC","",
                START.toString(),END.toString(),status.name(),"","","","","","")
                + line("HOURLY","41","Motion","MOTION","Europe/Istanbul","UTC","",
                END.toString(),END.plusSeconds(3600).toString(),"NO_SAMPLES",
                "0","0","0","","",""));
    }



    @ParameterizedTest
    @CsvSource({
            "0.0000,0",
            "1E+6,1000000",
            "1E-8,0.00000001",
            "-1.2500,-1.25"
    })
    @ResourceLock(Resources.LOCALE)
    void writesPlainDecimalNumbersIndependentlyOfTheFormattingLocale(String value,String expected) {
        Locale original = Locale.getDefault(Locale.Category.FORMAT);

        try {
            Locale.setDefault(Locale.Category.FORMAT,Locale.GERMANY);
            StatisticsSeriesPointDTO point = hour(START,COMPLETE,1L,
                    numeric(value,value,value,value),null,null,null);

            String result = write(sensor(TEMPERATURE,"Temperature"),HOURLY,point);

            assertThat(result).isEqualTo(NUMERIC_HEADER + line(
                    "HOURLY","41","Temperature","TEMPERATURE","Europe/Istanbul","UTC","",
                    START.toString(),END.toString(),"COMPLETE",
                    "1","C","CELSIUS","°C",expected,expected,expected,expected,"",""));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT,original);
        }
    }



    @ParameterizedTest
    @MethodSource("textCells")
    void escapesTextNeutralizesFormulasAndPreservesUnicode(String name,String expectedCell) {
        StatisticsSeriesPointDTO point = hour(START,NO_SAMPLES,0L,null,null,null,null);

        String result = write(sensor(TEMPERATURE,name),HOURLY,point);

        assertThat(result).isEqualTo(NUMERIC_HEADER + line(
                "HOURLY","41",expectedCell,"TEMPERATURE","Europe/Istanbul","UTC","",
                START.toString(),END.toString(),"NO_SAMPLES",
                "0","C","CELSIUS","°C","","","","","",""));
    }



    private static Stream<Arguments> textCells() {
        return Stream.of(
                Arguments.of("Plain","Plain"),
                Arguments.of("",""),
                Arguments.of("İç oda — 温度 🌡","İç oda — 温度 🌡"),
                Arguments.of("comma,name","\"comma,name\""),
                Arguments.of("quote\"name","\"quote\"\"name\""),
                Arguments.of("line\rbreak","\"line\rbreak\""),
                Arguments.of("line\nbreak","\"line\nbreak\""),
                Arguments.of("line\r\nbreak","\"line\r\nbreak\""),
                Arguments.of("=1+1","'=1+1"),
                Arguments.of("+1","'+1"),
                Arguments.of("-1","'-1"),
                Arguments.of("@SUM(1)","'@SUM(1)"),
                Arguments.of("  =1+1","'  =1+1"),
                Arguments.of("\u00A0+1","'\u00A0+1"),
                Arguments.of("\u2003@SUM(1)","'\u2003@SUM(1)"),
                Arguments.of(" \t=1+1","' \t=1+1"),
                Arguments.of("\tplain","'\tplain"),
                Arguments.of("\rplain","\"'\rplain\""),
                Arguments.of("\nplain","\"'\nplain\""),
                Arguments.of("=HYPERLINK(\"x\",\"y\")","\"'=HYPERLINK(\"\"x\"\",\"\"y\"\")\""),
                Arguments.of("'=1+1","'=1+1"),
                Arguments.of("room=1","room=1"),
                Arguments.of(" \tordinary"," \tordinary"),
                Arguments.of(" \t "," \t "),
                Arguments.of("A\0B","A\uFFFDB"));
    }



    private String write(StatisticsSensorDTO sensor,StatisticsResolution resolution,StatisticsSeriesPointDTO... rows) {
        Instant start = rows.length == 0 ? START : rows[0].bucketStart();
        Instant end = rows.length == 0 ? END : rows[rows.length - 1].bucketEnd();
        return writer.write(new SensorStatisticsExportDTO(sensor,start,end,resolution,List.of(rows)));
    }



    private static StatisticsSensorDTO sensor(SensorType type,String name) {
        return switch (type) {
            case TEMPERATURE -> new StatisticsSensorDTO(
                    41L,name,type,"Europe/Istanbul",MeasurementUnit.C,"CELSIUS","°C");
            case HUMIDITY -> new StatisticsSensorDTO(
                    41L,name,type,"Europe/Istanbul",MeasurementUnit.PERCENT,"PERCENT","% RH");
            case MOTION -> new StatisticsSensorDTO(
                    41L,name,type,"Europe/Istanbul",null,null,null);
        };
    }



    private static StatisticsSeriesPointDTO hour(Instant start,StatisticsPointStatus status,Long sampleCount,
                                                 StatisticsNumericMetricsDTO numeric,StatisticsMotionMetricsDTO motion,
                                                 Instant finalizedAt,Instant refreshedAt) {
        return new StatisticsSeriesPointDTO(
                StatisticsDisplayGranularity.HOURLY,null,null,start,start.plusSeconds(3600),
                null,null,null,status,sampleCount,numeric,motion,finalizedAt,refreshedAt);
    }



    private static StatisticsNumericMetricsDTO numeric(String sum,String minimum,String average,String maximum) {
        return new StatisticsNumericMetricsDTO(
                new BigDecimal(sum),new BigDecimal(minimum),new BigDecimal(average),new BigDecimal(maximum));
    }



    private static String line(String... cells) {
        return String.join(",",cells) + "\r\n";
    }
}