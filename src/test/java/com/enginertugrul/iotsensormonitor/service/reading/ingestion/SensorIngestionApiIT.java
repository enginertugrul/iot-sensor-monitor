package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.security.ingestion.GeneratedSensorIngestionToken;
import com.enginertugrul.iotsensormonitor.security.ingestion.SensorIngestionTokenGenerator;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;



@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
class SensorIngestionApiIT {

    private static final Instant SENSOR_CREATED_AT = TEST_INSTANT.minusSeconds(86_400);
    private static final Instant RECORDED_AT = TEST_INSTANT.minusSeconds(60).plusMillis(123);
    private static final String EPOCH_MILLIS = Long.toString(RECORDED_AT.toEpochMilli());

    private static HttpClient httpClient;

    @LocalServerPort
    private int port;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private SensorIngestionTokenGenerator tokenGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    private AppUser owner;

    @BeforeAll
    static void createHttpClient() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }


    @AfterAll
    static void closeHttpClient() {
        if (httpClient != null) {
            httpClient.close();
        }
    }


    @BeforeEach
    void setUp() {
        AppUser user = new AppUser("sensor-ingestion-api-" + UUID.randomUUID() + "@example.com","test-password-hash",SENSOR_CREATED_AT);
        user.verifyEmail(SENSOR_CREATED_AT);
        owner = appUserRepository.saveAndFlush(user);
    }



    @AfterEach
    void tearDown() {
        if (owner != null) {
            jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",owner.getId());
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",owner.getId());
        }
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,-273.15,-273.15,,C",
            "TEMPERATURE,-12.25,-12.25,,C",
            "HUMIDITY,0.0,0.0,,PERCENT",
            "HUMIDITY,58.75,58.75,,PERCENT",
            "HUMIDITY,100.0,100.0,,PERCENT",
            "MOTION,true,,true,",
            "MOTION,false,,false,"
    })
    void persistsDeviceFormsWithCanonicalValuesAndExactEpochMilliseconds(
            SensorType type,String wireValue,Double numericValue,Boolean booleanValue,MeasurementUnit unit) throws Exception {
        Fixture fixture = persistSensor(type);

        HttpResponse<String> response = postReading(type,fixture.rawToken(),wireValue,EPOCH_MILLIS);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEmpty();

        List<SensorReading> readings = readingsFor(fixture);
        assertThat(readings).hasSize(1);

        SensorReading reading = readings.getFirst();
        assertThat(reading.getId()).isPositive();
        assertThat(reading.getNumericValue()).isEqualTo(numericValue);
        assertThat(reading.getBooleanValue()).isEqualTo(booleanValue);
        assertThat(reading.getUnit()).isEqualTo(unit);
        assertThat(reading.getRecordedAt()).isEqualTo(RECORDED_AT);

        Sensor reloaded = sensorRepository.findById(fixture.sensor().getId()).orElseThrow();
        assertThat(reloaded.getFirstReadingAt()).isEqualTo(RECORDED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(reloaded.getCreatedAt()).isEqualTo(SENSOR_CREATED_AT);
        assertThat(reloaded.getIngestionTokenHash()).isEqualTo(fixture.sensor().getIngestionTokenHash());
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void rejectsUnknownTokensWithoutPersistingReadings(SensorType type) throws Exception {
        Fixture fixture = persistSensor(type);
        String unknownToken = tokenGenerator.generate().rawToken();

        HttpResponse<String> response = postReading(type,unknownToken,validValue(type),EPOCH_MILLIS);

        assertProblem(response,401,"INVALID_SENSOR_TOKEN");
        assertNoReadingOrHistoryChange(fixture);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void rejectsStoredHashesUsedAsRawTokens(SensorType type) throws Exception {
        Fixture fixture = persistSensor(type);

        HttpResponse<String> response = postReading(type,fixture.sensor().getIngestionTokenHash(),validValue(type),EPOCH_MILLIS);

        assertProblem(response,401,"INVALID_SENSOR_TOKEN");
        assertNoReadingOrHistoryChange(fixture);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void rejectsInactiveSensorsWithoutPersistingReadings(SensorType type) throws Exception {
        Fixture fixture = persistSensor(type);
        fixture.sensor().deactivate(TEST_INSTANT.minusSeconds(1));
        sensorRepository.saveAndFlush(fixture.sensor());

        HttpResponse<String> response = postReading(type,fixture.rawToken(),validValue(type),EPOCH_MILLIS);

        assertProblem(response,409,"INACTIVE_SENSOR");
        assertNoReadingOrHistoryChange(fixture);
        assertThat(sensorRepository.findById(fixture.sensor().getId()).orElseThrow().isActive()).isFalse();
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,HUMIDITY",
            "TEMPERATURE,MOTION",
            "HUMIDITY,TEMPERATURE",
            "HUMIDITY,MOTION",
            "MOTION,TEMPERATURE",
            "MOTION,HUMIDITY"
    })
    void rejectsTokensRegisteredForAnotherSensorType(SensorType registeredType,SensorType requestType) throws Exception {
        Fixture fixture = persistSensor(registeredType);

        HttpResponse<String> response = postReading(requestType,fixture.rawToken(),validValue(requestType),EPOCH_MILLIS);

        assertProblem(response,400,"INVALID_SENSOR_READING");
        assertNoReadingOrHistoryChange(fixture);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,-273.16",
            "TEMPERATURE,NaN",
            "TEMPERATURE,not-a-number",
            "HUMIDITY,-0.01",
            "HUMIDITY,100.01",
            "HUMIDITY,Infinity",
            "MOTION,sometimes"
    })
    void rejectsInvalidFormValuesWithoutPersistingReadings(SensorType type,String invalidValue) throws Exception {
        Fixture fixture = persistSensor(type);

        HttpResponse<String> response = postReading(type,fixture.rawToken(),invalidValue,EPOCH_MILLIS);

        assertProblem(response,400,"INVALID_REQUEST");
        assertNoReadingOrHistoryChange(fixture);
    }



    @ParameterizedTest
    @MethodSource("invalidRecordedAtValues")
    void rejectsOutOfPolicyDeviceTimestampsWithoutPersistingReadings(String recordedAt) throws Exception {
        Fixture fixture = persistSensor(SensorType.TEMPERATURE);

        HttpResponse<String> response = postReading(SensorType.TEMPERATURE,fixture.rawToken(),"21.5",recordedAt);

        assertProblem(response,400,"INVALID_SENSOR_READING");
        assertNoReadingOrHistoryChange(fixture);
    }



    private Fixture persistSensor(SensorType type) {
        GeneratedSensorIngestionToken token = tokenGenerator.generate();
        Sensor sensor = new Sensor(owner,type,"HTTP " + type,"Istanbul","Kadikoy","Window","Europe/Istanbul",SENSOR_CREATED_AT);
        sensor.assignIngestionTokenHash(token.tokenHash(),SENSOR_CREATED_AT);
        return new Fixture(sensorRepository.saveAndFlush(sensor),token.rawToken());
    }



    private List<SensorReading> readingsFor(Fixture fixture) {
        return readingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(fixture.sensor().getId(),owner.getId());
    }



    private void assertNoReadingOrHistoryChange(Fixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sensor_readings WHERE sensor_id=?",Long.class,fixture.sensor().getId());
        assertThat(count).isZero();

        Sensor reloaded = sensorRepository.findById(fixture.sensor().getId()).orElseThrow();
        assertThat(reloaded.getFirstReadingAt()).isNull();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(fixture.sensor().getUpdatedAt());
        assertThat(reloaded.getIngestionTokenHash()).isEqualTo(fixture.sensor().getIngestionTokenHash());
    }



    private HttpResponse<String> postReading(SensorType type,String token,String value,String recordedAt) throws Exception {
        String path = switch (type) {
            case TEMPERATURE -> "/readings/temperature";
            case HUMIDITY -> "/readings/humidity";
            case MOTION -> "/readings/motion";
        };

        String valueField = switch (type) {
            case TEMPERATURE -> "celsiusValue";
            case HUMIDITY -> "humidityPercentage";
            case MOTION -> "motionDetected";
        };

        String body = "sensorToken=" + encode(token)
                + "&" + valueField + "=" + encode(value)
                + "&recordedAt=" + encode(recordedAt);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8))
                .build();

        return httpClient.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }



    private void assertProblem(HttpResponse<String> response,int expectedStatus,String expectedCode) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);

        MediaType contentType = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(jsonMapper.readTree(response.body()).path("code").asString()).isEqualTo(expectedCode);
    }



    private static String validValue(SensorType type) {
        return switch (type) {
            case TEMPERATURE -> "21.5";
            case HUMIDITY -> "58.75";
            case MOTION -> "true";
        };
    }



    private static String encode(String value) {
        return URLEncoder.encode(value,StandardCharsets.UTF_8);
    }



    private static Stream<String> invalidRecordedAtValues() {
        return Stream.of(
                Long.toString(SENSOR_CREATED_AT.minusMillis(1).toEpochMilli()),
                Long.toString(TEST_INSTANT.minusSeconds(21_600).minusMillis(1).toEpochMilli()),
                Long.toString(TEST_INSTANT.plusSeconds(120).plusMillis(1).toEpochMilli()),
                Long.toString(RECORDED_AT.getEpochSecond())
        );
    }

    private record Fixture(Sensor sensor,String rawToken) {
    }
}