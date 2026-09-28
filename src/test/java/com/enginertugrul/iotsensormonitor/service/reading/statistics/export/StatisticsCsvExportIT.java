package com.enginertugrul.iotsensormonitor.service.reading.statistics.export;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.*;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.*;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.*;
import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.HOURLY_TO_DAILY;
import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.RAW_TO_HOURLY;
import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-test.properties",
        "app.sensor-data.statistics.chart-point-budget=2",
        "app.sensor-data.statistics.csv-export-row-limit=3"
})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class StatisticsCsvExportIT {

    private static final Instant CREATED_AT = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant START = Instant.parse("2026-01-10T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private static final Instant FINALIZED_AT = TEST_INSTANT.minusSeconds(60);

    @Autowired
    private StatisticsCsvExportService service;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private HourlySensorSummaryRepository hourlyRepository;

    @Autowired
    private DailySensorSummaryRepository dailyRepository;

    @Autowired
    private SensorRollupCheckpointRepository checkpointRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> ownerIds = new ArrayList<>();

    private TransactionTemplate transactions;
    private AppUser owner;



    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(20);
        owner = persistOwner();
    }



    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            for (Long ownerId : ownerIds) {
                jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",ownerId);
                jdbcTemplate.update("DELETE FROM app_users WHERE id=?",ownerId);
            }
        });
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,CELSIUS,CELSIUS,°C,30,10,15,20",
            "TEMPERATURE,FAHRENHEIT,FAHRENHEIT,°F,118,50,59,68",
            "TEMPERATURE,KELVIN,KELVIN,K,576.3,283.15,288.15,293.15",
            "HUMIDITY,FAHRENHEIT,PERCENT,% RH,30,10,15,20"
    })
    void exportsStoredNumericSummariesWithDisplayUnitsAndEscapedNames(
            SensorType type,TemperatureUnit preference,String metricUnit,String symbol,
            String sum,String minimum,String average,String maximum) {
        Sensor sensor = persistSensor(owner,type,"=İç, \"oda\"","Europe/Istanbul",START.plusSeconds(900));
        MeasurementUnit canonicalUnit = type == SensorType.TEMPERATURE ? MeasurementUnit.C : MeasurementUnit.PERCENT;
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(
                2,canonicalUnit,new BigDecimal("30"),10.0,20.0);
        persistHourlySummary(sensor,START,aggregate);

        StatisticsCsvExport result = service.createExport(
                sensor.getId(),owner.getId(),START,END,HOURLY,preference);

        assertThat(result.fileName()).isEqualTo(
                "sensor-" + sensor.getId() + "-statistics-hourly-20260110T000000Z-to-20260110T010000Z.csv");
        assertThat(dataRows(result)).hasSize(1);
        assertThat(result.content()).endsWith(line(
                "HOURLY",sensor.getId().toString(),"\"'=İç, \"\"oda\"\"\"",type.name(),
                "Europe/Istanbul","UTC","",START.toString(),END.toString(),"COMPLETE",
                "2",canonicalUnit.name(),metricUnit,symbol,sum,minimum,average,maximum,
                FINALIZED_AT.toString(),TEST_INSTANT.toString()));
    }



    @Test
    void exportsStoredMotionCountsAndPercentage() {
        Sensor sensor = persistSensor(owner,SensorType.MOTION,"Motion","UTC",START.plusSeconds(900));
        persistHourlySummary(sensor,START,SensorSummaryAggregate.booleanSamples(4,1));

        StatisticsCsvExport result = service.createExport(
                sensor.getId(),owner.getId(),START,END,HOURLY,TemperatureUnit.FAHRENHEIT);

        assertThat(dataRows(result)).hasSize(1);
        assertThat(result.content()).endsWith(line(
                "HOURLY",sensor.getId().toString(),"Motion","MOTION","UTC","UTC","",
                START.toString(),END.toString(),"COMPLETE",
                "4","1","3","25",FINALIZED_AT.toString(),TEST_INSTANT.toString()));
    }



    @ParameterizedTest
    @CsvSource({
            "2025-03-30,2025-03-29T23:00:00Z,2025-03-30T22:00:00Z",
            "2025-10-26,2025-10-25T22:00:00Z,2025-10-26T23:00:00Z"
    })
    void exportsDailySummariesWithTheirActualDstBoundaries(String dateText,String startText,String endText) {
        LocalDate date = LocalDate.parse(dateText);
        Instant start = Instant.parse(startText);
        Instant end = Instant.parse(endText);
        Sensor sensor = persistSensor(
                owner,SensorType.TEMPERATURE,"Berlin","Europe/Berlin",start.plusSeconds(900));
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(
                2,MeasurementUnit.C,new BigDecimal("30"),10.0,20.0);
        persistDailySummary(sensor,date,aggregate);

        StatisticsCsvExport result = service.createExport(
                sensor.getId(),owner.getId(),start,end,DAILY,TemperatureUnit.CELSIUS);

        assertThat(result.fileName()).contains("-statistics-daily-");
        assertThat(dataRows(result)).hasSize(1);
        assertThat(result.content()).endsWith(line(
                "DAILY",sensor.getId().toString(),"Berlin","TEMPERATURE",
                "Europe/Berlin","Europe/Berlin",dateText,startText,endText,"COMPLETE",
                "2","C","CELSIUS","°C","30","10","15","20",
                FINALIZED_AT.toString(),TEST_INSTANT.toString()));
    }



    @Test
    void exportsUnavailableRollupMetricsAsBlankCells() {
        Sensor sensor = persistSensor(
                owner,SensorType.TEMPERATURE,"Delayed","UTC",START.plusSeconds(900));

        StatisticsCsvExport result = service.createExport(
                sensor.getId(),owner.getId(),START,END,HOURLY,TemperatureUnit.CELSIUS);

        assertThat(dataRows(result)).hasSize(1);
        assertThat(result.content()).endsWith(line(
                "HOURLY",sensor.getId().toString(),"Delayed","TEMPERATURE","UTC","UTC","",
                START.toString(),END.toString(),"ROLLUP_DELAY",
                "","C","CELSIUS","°C","","","","","",""));
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"HOURLY","DAILY"})
    void acceptsExactlyTheCsvRowLimitAndRejectsOneAdditionalTouchedBucket(StatisticsResolution resolution) {
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Limit","UTC",null);
        long secondsPerRow = resolution == HOURLY ? 3600 : 86400;
        Instant end = START.plusSeconds(3 * secondsPerRow);

        StatisticsCsvExport result = service.createExport(
                sensor.getId(),owner.getId(),START,end,resolution,TemperatureUnit.CELSIUS);

        assertThat(dataRows(result)).hasSize(3).allSatisfy(row -> {
            assertThat(row).startsWith(resolution.name() + "," + sensor.getId() + ",Limit,");
            assertThat(row).endsWith(",NO_SAMPLES,0,C,CELSIUS,°C,,,,,,");
        });

        assertThatThrownBy(() -> service.createExport(
                sensor.getId(),owner.getId(),START,end.plusNanos(1),resolution,TemperatureUnit.CELSIUS))
                .isExactlyInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("The requested " + resolution
                        + " CSV export would contain 4 rows, exceeding the configured limit of 3");
    }



    @Test
    void autoChoosesAnExportResolutionThatFitsTheCsvLimit() {
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Automatic","UTC",null);

        StatisticsCsvExport hourly = service.createExport(
                sensor.getId(),owner.getId(),START,START.plusSeconds(3 * 3600),AUTO,TemperatureUnit.CELSIUS);

        assertThat(hourly.fileName()).contains("-statistics-hourly-");
        assertThat(dataRows(hourly)).hasSize(3)
                .allSatisfy(row -> assertThat(row).startsWith("HOURLY,"));

        StatisticsCsvExport daily = service.createExport(
                sensor.getId(),owner.getId(),START,START.plusSeconds(4 * 3600),AUTO,TemperatureUnit.CELSIUS);

        assertThat(daily.fileName()).contains("-statistics-daily-");
        assertThat(dataRows(daily)).hasSize(1)
                .allSatisfy(row -> assertThat(row).startsWith("DAILY,"));

        assertThatThrownBy(() -> service.createExport(
                sensor.getId(),owner.getId(),START,START.plusSeconds(4 * 86400),AUTO,TemperatureUnit.CELSIUS))
                .isExactlyInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("The requested DAILY CSV export would contain 4 rows, exceeding the configured limit of 3");
    }



    @Test
    void rejectsRawExportsThroughTheRealQueryPipeline() {
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Raw","UTC",null);

        assertThatThrownBy(() -> service.createExport(
                sensor.getId(),owner.getId(),START,END,RAW,TemperatureUnit.CELSIUS))
                .isExactlyInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("Raw-reading CSV export is not available");
    }



    @Test
    void missingAndForeignSensorsHaveTheSameNotFoundBehavior() {
        AppUser otherOwner = persistOwner();
        Sensor foreign = persistSensor(otherOwner,SensorType.TEMPERATURE,"Foreign","UTC",null);

        for (Long sensorId : List.of(-1L,foreign.getId())) {
            assertThatThrownBy(() -> service.createExport(
                    sensorId,owner.getId(),START,END,HOURLY,TemperatureUnit.CELSIUS))
                    .isExactlyInstanceOf(SensorNotFoundException.class)
                    .hasMessage("Sensor not found")
                    .hasNoCause();
        }
    }



    @Test
    void usesTheClippedEndInBothTheFilenameAndCsvPeriod() {
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Future","UTC",null);
        Instant start = TEST_INSTANT.minusSeconds(3600);
        Instant requestedEnd = TEST_INSTANT.plusSeconds(86400);

        StatisticsCsvExport result = service.createExport(
                sensor.getId(),owner.getId(),start,requestedEnd,HOURLY,TemperatureUnit.CELSIUS);

        assertThat(result.fileName()).isEqualTo(
                "sensor-" + sensor.getId() + "-statistics-hourly-20260115T110000Z-to-20260115T120000Z.csv");
        assertThat(dataRows(result)).hasSize(1);
        assertThat(result.content()).endsWith(line(
                "HOURLY",sensor.getId().toString(),"Future","TEMPERATURE","UTC","UTC","",
                start.toString(),TEST_INSTANT.toString(),"PARTIAL",
                "0","C","CELSIUS","°C","","","","","",""));
    }



    private AppUser persistOwner() {
        AppUser saved = userRepository.saveAndFlush(new AppUser(
                "csv-" + UUID.randomUUID() + "@example.com","test-password-hash",CREATED_AT));
        ownerIds.add(saved.getId());
        return saved;
    }



    private Sensor persistSensor(AppUser sensorOwner,SensorType type,String name,String timezone,Instant firstReadingAt) {
        Sensor sensor = new Sensor(sensorOwner,type,name,"Istanbul","Kadikoy","Window",timezone,CREATED_AT);

        if (firstReadingAt != null) {
            sensor.recordFirstReading(firstReadingAt,TEST_INSTANT);
        }

        return sensorRepository.saveAndFlush(sensor);
    }



    private void persistHourlySummary(Sensor sensor,Instant start,SensorSummaryAggregate aggregate) {
        transactions.executeWithoutResult(status -> {
            Sensor managed = sensorRepository.findById(sensor.getId()).orElseThrow();
            HourlySensorSummary summary = HourlySensorSummary.create(managed,start,aggregate,FINALIZED_AT);
            summary.refresh(aggregate,TEST_INSTANT);
            hourlyRepository.save(summary);
            persistCoverage(managed,RAW_TO_HOURLY,start,start.plusSeconds(3600));
        });
    }



    private void persistDailySummary(Sensor sensor,LocalDate date,SensorSummaryAggregate aggregate) {
        transactions.executeWithoutResult(status -> {
            Sensor managed = sensorRepository.findById(sensor.getId()).orElseThrow();
            ZoneId zone = ZoneId.of(managed.getTimezone());
            DailySensorSummary summary = DailySensorSummary.create(managed,date,zone,aggregate,FINALIZED_AT);
            summary.refresh(aggregate,TEST_INSTANT);
            dailyRepository.save(summary);
            persistCoverage(managed,HOURLY_TO_DAILY,
                    date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant());
        });
    }



    private void persistCoverage(Sensor sensor,RollupStage stage,Instant start,Instant end) {
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,stage,start,TEST_INSTANT);
        checkpoint.recordAttempt(start,TEST_INSTANT);
        checkpoint.advanceContiguously(start,end,TEST_INSTANT);
        checkpointRepository.saveAndFlush(checkpoint);
    }



    private static List<String> dataRows(StatisticsCsvExport export) {
        return export.content().lines().skip(1).toList();
    }



    private static String line(String... cells) {
        return String.join(",",cells) + "\r\n";
    }
}