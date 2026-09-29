package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.*;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.RAW_TO_HOURLY;
import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class StatisticsApiIT {

    private static final Instant START = TEST_INSTANT.minusSeconds(7200);
    private static final Instant END = START.plusSeconds(3600);
    private static final Instant CREATED_AT = START.minusSeconds(86400);
    private static final Instant FINALIZED_AT = TEST_INSTANT.minusSeconds(60);
    private static final String SENSOR_NAME = "Çalışma odası";
    private static final MediaType CSV_MEDIA_TYPE = new MediaType("text","csv",StandardCharsets.UTF_8);

    private static final String NUMERIC_HEADER = "granularity,sensor_id,sensor_name,sensor_type,sensor_time_zone,period_time_zone,local_date,"
            + "period_start_utc,period_end_utc,status,sample_count,canonical_storage_unit,metric_unit,metric_unit_symbol,"
            + "sum,minimum,average,maximum,finalized_at_utc,refreshed_at_utc\r\n";
    private static final String MOTION_HEADER = "granularity,sensor_id,sensor_name,sensor_type,sensor_time_zone,period_time_zone,local_date,"
            + "period_start_utc,period_end_utc,status,total_sample_count,true_sample_count,false_sample_count,true_percentage,"
            + "finalized_at_utc,refreshed_at_utc\r\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private HourlySensorSummaryRepository hourlyRepository;

    @Autowired
    private SensorRollupCheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> ownerIds = new ArrayList<>();

    private AppUser owner;
    private AuthenticatedUser principal;



    @BeforeEach
    void setUp() {
        owner = persistOwner();
        principal = new AuthenticatedUser(owner);
        principal.eraseCredentials();
    }



    @AfterEach
    void tearDown() {
        for (Long ownerId : ownerIds) {
            jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",ownerId);
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",ownerId);
        }
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,CELSIUS,C,CELSIUS,°C,30,10,15,20",
            "TEMPERATURE,FAHRENHEIT,C,FAHRENHEIT,°F,118,50,59,68",
            "TEMPERATURE,KELVIN,C,KELVIN,K,576.3,283.15,288.15,293.15",
            "HUMIDITY,FAHRENHEIT,PERCENT,PERCENT,% RH,30,10,15,20"
    })
    void servesNumericJsonAndCsvUsingTheCurrentStoredPreference(
            SensorType type,TemperatureUnit preference,MeasurementUnit canonicalUnit,String displayUnit,String symbol,
            String sum,String minimum,String average,String maximum) throws Exception {
        updatePreference(preference);
        Dataset dataset = persistDataset(type);
        Long sensorId = dataset.sensor().getId();

        MvcResult jsonResult = mockMvc.perform(request(sensorId,"series",START,END,"HOURLY")
                        .param("ownerId","-999")
                        .param("temperatureUnit","CELSIUS"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode body = json(jsonResult);
        assertThat(body.at("/sensor/id").longValue()).isEqualTo(sensorId);
        assertThat(body.at("/sensor/name").asString()).isEqualTo(SENSOR_NAME);
        assertThat(body.at("/sensor/type").asString()).isEqualTo(type.name());
        assertThat(body.at("/sensor/timeZoneId").asString()).isEqualTo("Europe/Istanbul");
        assertThat(body.at("/sensor/canonicalUnit").asString()).isEqualTo(canonicalUnit.name());
        assertThat(body.at("/sensor/displayUnit").asString()).isEqualTo(displayUnit);
        assertThat(body.at("/sensor/displayUnitSymbol").asString()).isEqualTo(symbol);
        assertThat(body.path("requestedStartInclusive").asString()).isEqualTo(START.toString());
        assertThat(body.path("requestedEndExclusive").asString()).isEqualTo(END.toString());
        assertThat(body.path("evaluatedStartInclusive").asString()).isEqualTo(START.toString());
        assertThat(body.path("evaluatedEndExclusive").asString()).isEqualTo(END.toString());
        assertThat(body.path("asOf").asString()).isEqualTo(TEST_INSTANT.toString());
        assertThat(body.path("requestedResolution").asString()).isEqualTo("HOURLY");
        assertThat(body.path("resolvedResolution").asString()).isEqualTo("HOURLY");
        assertThat(body.path("displayGranularity").asString()).isEqualTo("HOURLY");
        assertThat(body.path("status").asString()).isEqualTo("COMPLETE");
        assertThat(body.path("fullyCovered").booleanValue()).isTrue();
        assertThat(body.at("/periodMetrics/available").booleanValue()).isTrue();
        assertThat(body.at("/periodMetrics/sourceSampleCount").longValue()).isEqualTo(2);
        assertThat(body.at("/periodMetrics/motionMetrics").isNull()).isTrue();
        assertNumeric(body.at("/periodMetrics/numericMetrics"),sum,minimum,average,maximum);

        assertThat(body.path("points").isArray()).isTrue();
        assertThat(body.path("points").size()).isEqualTo(1);
        JsonNode point = body.path("points").get(0);
        assertThat(point.path("granularity").asString()).isEqualTo("HOURLY");
        assertThat(point.path("sourceReadingId").isNull()).isTrue();
        assertThat(point.path("recordedAt").isNull()).isTrue();
        assertThat(point.path("bucketStart").asString()).isEqualTo(START.toString());
        assertThat(point.path("bucketEnd").asString()).isEqualTo(END.toString());
        assertThat(point.path("status").asString()).isEqualTo("COMPLETE");
        assertThat(point.path("sourceSampleCount").longValue()).isEqualTo(2);
        assertThat(point.path("finalizedAt").asString()).isEqualTo(FINALIZED_AT.toString());
        assertThat(point.path("refreshedAt").asString()).isEqualTo(TEST_INSTANT.toString());
        assertNumeric(point.path("numericMetrics"),sum,minimum,average,maximum);

        assertThat(body.at("/coverage/hourly/rollupProgress/safeThroughExclusive").asString()).isEqualTo(END.toString());
        assertThat(body.at("/csvExport/available").booleanValue()).isTrue();
        assertThat(body.at("/csvExport/rowCount").intValue()).isEqualTo(1);

        MvcResult csvResult = mockMvc.perform(request(sensorId,"export.csv",START,END,null)
                        .param("ownerId","-999")
                        .param("temperatureUnit","CELSIUS"))
                .andExpect(status().isOk())
                .andReturn();

        String expectedCsv = NUMERIC_HEADER + String.join(",",
                "HOURLY",sensorId.toString(),SENSOR_NAME,type.name(),"Europe/Istanbul","UTC","",
                START.toString(),END.toString(),"COMPLETE","2",canonicalUnit.name(),displayUnit,symbol,
                sum,minimum,average,maximum,FINALIZED_AT.toString(),TEST_INSTANT.toString()) + "\r\n";

        assertCsvDownload(csvResult,sensorId,expectedCsv);
    }



    @Test
    void defaultsShortJsonRequestsToRawAndExcludesTheEndBoundaryReading() throws Exception {
        updatePreference(TemperatureUnit.FAHRENHEIT);
        Dataset dataset = persistDataset(SensorType.TEMPERATURE);

        MvcResult result = mockMvc.perform(request(dataset.sensor().getId(),"series",START,END,null))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode body = json(result);
        assertThat(body.path("requestedResolution").asString()).isEqualTo("AUTO");
        assertThat(body.path("resolvedResolution").asString()).isEqualTo("RAW");
        assertThat(body.path("displayGranularity").asString()).isEqualTo("RAW");
        assertThat(body.path("status").asString()).isEqualTo("COMPLETE");
        assertThat(body.path("points").size()).isEqualTo(2);

        JsonNode first = body.path("points").get(0);
        JsonNode second = body.path("points").get(1);
        assertThat(first.path("sourceReadingId").longValue()).isEqualTo(dataset.firstReadingId());
        assertThat(second.path("sourceReadingId").longValue()).isEqualTo(dataset.secondReadingId());
        assertThat(first.path("recordedAt").asString()).isEqualTo(START.toString());
        assertThat(second.path("recordedAt").asString()).isEqualTo(START.plusSeconds(1800).toString());
        assertThat(first.path("bucketStart").isNull()).isTrue();
        assertThat(second.path("bucketEnd").isNull()).isTrue();
        assertNumeric(first.path("numericMetrics"),"50","50","50","50");
        assertNumeric(second.path("numericMetrics"),"68","68","68","68");

        assertThat(body.at("/periodMetrics/sourceSampleCount").longValue()).isEqualTo(2);
        assertNumeric(body.at("/periodMetrics/numericMetrics"),"118","50","59","68");
        assertThat(body.at("/csvExport/available").booleanValue()).isFalse();
        assertThat(body.at("/csvExport/rowCount").intValue()).isZero();
    }



    @Test
    void servesMotionJsonAndCsvWithCountsAndPercentages() throws Exception {
        updatePreference(TemperatureUnit.KELVIN);
        Dataset dataset = persistDataset(SensorType.MOTION);
        Long sensorId = dataset.sensor().getId();

        MvcResult jsonResult = mockMvc.perform(request(sensorId,"series",START,END,"RAW"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode body = json(jsonResult);
        assertThat(body.at("/sensor/type").asString()).isEqualTo("MOTION");
        assertThat(body.at("/sensor/canonicalUnit").isNull()).isTrue();
        assertThat(body.at("/sensor/displayUnit").isNull()).isTrue();
        assertThat(body.at("/sensor/displayUnitSymbol").isNull()).isTrue();
        assertThat(body.path("status").asString()).isEqualTo("COMPLETE");
        assertThat(body.at("/periodMetrics/numericMetrics").isNull()).isTrue();

        JsonNode metrics = body.at("/periodMetrics/motionMetrics");
        assertThat(metrics.path("totalSampleCount").longValue()).isEqualTo(2);
        assertThat(metrics.path("trueSampleCount").longValue()).isEqualTo(1);
        assertThat(metrics.path("falseSampleCount").longValue()).isEqualTo(1);
        assertThat(metrics.path("truePercentage").decimalValue()).isEqualByComparingTo("50");

        assertThat(body.path("points").size()).isEqualTo(2);
        JsonNode first = body.path("points").get(0);
        JsonNode second = body.path("points").get(1);
        assertThat(first.path("sourceReadingId").longValue()).isEqualTo(dataset.firstReadingId());
        assertThat(second.path("sourceReadingId").longValue()).isEqualTo(dataset.secondReadingId());
        assertThat(first.path("numericMetrics").isNull()).isTrue();
        assertThat(second.path("numericMetrics").isNull()).isTrue();
        assertThat(first.at("/motionMetrics/trueSampleCount").longValue()).isEqualTo(1);
        assertThat(first.at("/motionMetrics/falseSampleCount").longValue()).isZero();
        assertThat(first.at("/motionMetrics/truePercentage").decimalValue()).isEqualByComparingTo("100");
        assertThat(second.at("/motionMetrics/trueSampleCount").longValue()).isZero();
        assertThat(second.at("/motionMetrics/falseSampleCount").longValue()).isEqualTo(1);
        assertThat(second.at("/motionMetrics/truePercentage").decimalValue()).isEqualByComparingTo("0");

        MvcResult csvResult = mockMvc.perform(request(sensorId,"export.csv",START,END,"HOURLY"))
                .andExpect(status().isOk())
                .andReturn();

        String expectedCsv = MOTION_HEADER + String.join(",",
                "HOURLY",sensorId.toString(),SENSOR_NAME,"MOTION","Europe/Istanbul","UTC","",
                START.toString(),END.toString(),"COMPLETE","2","1","1","50",
                FINALIZED_AT.toString(),TEST_INSTANT.toString()) + "\r\n";

        assertCsvDownload(csvResult,sensorId,expectedCsv);
    }



    @Test
    void returnsKnownEmptyJsonForAnOwnedSensorWithoutReadings() throws Exception {
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,null);

        MvcResult result = mockMvc.perform(request(sensor.getId(),"series",START,END,"RAW"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode body = json(result);
        assertThat(body.path("status").asString()).isEqualTo("NO_SAMPLES");
        assertThat(body.path("fullyCovered").booleanValue()).isTrue();
        assertThat(body.path("points").isArray()).isTrue();
        assertThat(body.path("points").size()).isZero();
        assertThat(body.at("/periodMetrics/available").booleanValue()).isTrue();
        assertThat(body.at("/periodMetrics/sourceSampleCount").longValue()).isZero();
        assertThat(body.at("/periodMetrics/numericMetrics").isNull()).isTrue();
        assertThat(body.at("/periodMetrics/motionMetrics").isNull()).isTrue();
    }



    @ParameterizedTest
    @ValueSource(strings = {"series","export.csv"})
    void missingAndForeignSensorsReturnTheSameNotFoundContract(String endpoint) throws Exception {
        AppUser otherOwner = persistOwner();
        Sensor foreignSensor = persistSensor(otherOwner,SensorType.TEMPERATURE,null);

        MvcResult missingResult = mockMvc.perform(request(-1L,endpoint,START,END,"HOURLY")
                        .param("ownerId",otherOwner.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SENSOR_NOT_FOUND"))
                .andReturn();

        MvcResult foreignResult = mockMvc.perform(request(foreignSensor.getId(),endpoint,START,END,"HOURLY")
                        .param("ownerId",otherOwner.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SENSOR_NOT_FOUND"))
                .andReturn();

        JsonNode missing = json(missingResult);
        JsonNode foreign = json(foreignResult);
        for (String field : List.of("type","title","status","detail","code")) {
            assertThat(foreign.path(field)).as(field).isEqualTo(missing.path(field));
        }
        assertThat(foreign.path("detail").asString()).isEqualTo("The requested sensor was not found");
    }



    @ParameterizedTest
    @ValueSource(strings = {"series","export.csv"})
    void rejectsAnEmptyTimeRangeThroughTheRealQueryPipeline(String endpoint) throws Exception {
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,null);

        mockMvc.perform(request(sensor.getId(),endpoint,START,START,"HOURLY"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_STATISTICS_QUERY"));
    }



    @Test
    void rejectsRawCsvThroughTheRealQueryPipeline() throws Exception {
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,null);

        mockMvc.perform(request(sensor.getId(),"export.csv",START,END,"RAW"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_STATISTICS_QUERY"));
    }



    private MockHttpServletRequestBuilder request(Long sensorId,String endpoint,Instant start,Instant end,String resolution) {
        MediaType acceptedType = endpoint.equals("series") ? MediaType.APPLICATION_JSON : CSV_MEDIA_TYPE;
        MockHttpServletRequestBuilder request = get("/api/sensors/{sensorId}/statistics/{endpoint}",sensorId,endpoint)
                .with(user(principal))
                .accept(acceptedType,MediaType.APPLICATION_PROBLEM_JSON)
                .param("startInclusive",start.toString())
                .param("endExclusive",end.toString());

        if (resolution != null) {
            request.param("resolution",resolution);
        }
        return request;
    }



    private AppUser persistOwner() {
        AppUser user = new AppUser("statistics-api-" + UUID.randomUUID() + "@example.com","test-password-hash",CREATED_AT);
        user.verifyEmail(CREATED_AT);
        AppUser saved = userRepository.saveAndFlush(user);
        ownerIds.add(saved.getId());
        return saved;
    }



    private void updatePreference(TemperatureUnit preference) {
        owner.updatePreferences(PreferredLanguage.ENGLISH,preference,"Asia/Tokyo",TEST_INSTANT);
        owner = userRepository.saveAndFlush(owner);
    }



    private Sensor persistSensor(AppUser sensorOwner,SensorType type,Instant firstReadingAt) {
        Sensor sensor = new Sensor(sensorOwner,type,SENSOR_NAME,"Istanbul","Kadikoy","Window","Europe/Istanbul",CREATED_AT);
        if (firstReadingAt != null) {
            sensor.recordFirstReading(firstReadingAt,TEST_INSTANT);
        }
        return sensorRepository.saveAndFlush(sensor);
    }



    private Dataset persistDataset(SensorType type) {
        Sensor sensor = persistSensor(owner,type,START);
        SensorReading first = persistReading(sensor,10.0,true,START);
        SensorReading second = persistReading(sensor,20.0,false,START.plusSeconds(1800));
        persistReading(sensor,90.0,true,END);

        SensorSummaryAggregate aggregate;
        if (type == SensorType.MOTION) {
            aggregate = SensorSummaryAggregate.booleanSamples(2,1);
        } else {
            MeasurementUnit unit = type == SensorType.TEMPERATURE ? MeasurementUnit.C : MeasurementUnit.PERCENT;
            aggregate = SensorSummaryAggregate.numeric(2,unit,new BigDecimal("30"),10.0,20.0);
        }

        HourlySensorSummary summary = HourlySensorSummary.create(sensor,START,aggregate,FINALIZED_AT);
        summary.refresh(aggregate,TEST_INSTANT);
        hourlyRepository.saveAndFlush(summary);

        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,RAW_TO_HOURLY,START,TEST_INSTANT);
        checkpoint.recordAttempt(START,TEST_INSTANT);
        checkpoint.advanceContiguously(START,END,TEST_INSTANT);
        checkpointRepository.saveAndFlush(checkpoint);

        return new Dataset(sensor,first.getId(),second.getId());
    }



    private SensorReading persistReading(Sensor sensor,double value,boolean detected,Instant recordedAt) {
        SensorReading reading = switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,value,recordedAt);
            case HUMIDITY -> SensorReading.humidity(sensor,value,recordedAt);
            case MOTION -> SensorReading.motion(sensor,detected,recordedAt);
        };
        return readingRepository.saveAndFlush(reading);
    }



    private JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    }



    private void assertNumeric(JsonNode metrics,String sum,String minimum,String average,String maximum) {
        assertThat(metrics.isObject()).isTrue();
        assertThat(metrics.path("sum").decimalValue()).isEqualByComparingTo(sum);
        assertThat(metrics.path("minimum").decimalValue()).isEqualByComparingTo(minimum);
        assertThat(metrics.path("average").decimalValue()).isEqualByComparingTo(average);
        assertThat(metrics.path("maximum").decimalValue()).isEqualByComparingTo(maximum);
    }



    private void assertCsvDownload(MvcResult result,Long sensorId,String expectedCsv) throws Exception {
        byte[] expectedBytes = expectedCsv.getBytes(StandardCharsets.UTF_8);
        String expectedFileName = "sensor-" + sensorId
                + "-statistics-hourly-20260115T100000Z-to-20260115T110000Z.csv";

        content().contentType(CSV_MEDIA_TYPE).match(result);
        content().bytes(expectedBytes).match(result);
        header().string(HttpHeaders.CONTENT_LENGTH,Integer.toString(expectedBytes.length)).match(result);
        header().string(HttpHeaders.CACHE_CONTROL,"no-store").match(result);
        header().string("X-Content-Type-Options","nosniff").match(result);

        String headerValue = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(headerValue).isNotBlank();
        ContentDisposition disposition = ContentDisposition.parse(headerValue);
        assertThat(disposition.getType()).isEqualTo("attachment");
        assertThat(disposition.getFilename()).isEqualTo(expectedFileName);
    }



    private record Dataset(Sensor sensor,Long firstReadingId,Long secondReadingId) {
    }
}