package com.enginertugrul.iotsensormonitor.service.reading.stream;

import com.enginertugrul.iotsensormonitor.config.SensorReadingStreamConfig;
import com.enginertugrul.iotsensormonitor.controller.SensorReadingStreamController;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.testsupport.ControlledStreamExecutor;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.request.async.WebAsyncUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-test.properties",
        "app.sensor-data.stream.refresh-interval=PT2S",
        "app.sensor-data.stream.heartbeat-interval=PT6S",
        "app.sensor-data.stream.connection-lifetime=PT30S",
        "app.sensor-data.stream.work-timeout=PT10S",
        "app.sensor-data.stream.maximum-subscriptions=1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
class SensorReadingStreamIT {

    private static final String STREAM_PATH = "/api/sensors/{sensorId}/readings/stream";
    private static final String PASSWORD = "Stream-test-password-42!";
    private static final String LOCATION = "Çalışma odası";

    private static final Instant CREATED_AT = TEST_INSTANT.minusSeconds(86_400);
    private static final Instant RECORDED_AT = TEST_INSTANT.minusSeconds(60);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SensorReadingStreamService streamService;

    @Autowired
    private SensorReadingStreamPolicy policy;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @TestBean(name = SensorReadingStreamConfig.STREAM_EXECUTOR,enforceOverride = true)
    private ThreadPoolTaskExecutor streamExecutor;

    @TestBean(name = SensorReadingStreamConfig.STREAM_NANO_TIME_SOURCE,enforceOverride = true)
    private LongSupplier nanoTimeSource;

    private final List<Long> fixtureOwnerIds = new ArrayList<>();
    private final List<String> fixtureSessionIds = new ArrayList<>();
    private final List<MvcResult> streams = new ArrayList<>();

    private ControlledStreamExecutor executor;
    private MutableNanoTime nanoTime;



    @BeforeEach
    void setUp() {
        executor = (ControlledStreamExecutor) streamExecutor;
        nanoTime = (MutableNanoTime) nanoTimeSource;

        assertThat(executor.pendingTasks()).isZero();
        nanoTime.reset();
    }



    @AfterEach
    void tearDown() throws Exception {
        try {
            for (String sessionId : fixtureSessionIds) {
                streamService.closeSessionStreams(sessionId);
            }

            executor.runAll();

            for (MvcResult stream : streams) {
                if (stream.getRequest().isAsyncStarted()) {
                    stream.getAsyncResult(1000);
                    mockMvc.perform(asyncDispatch(stream));
                }
            }
        } finally {
            for (String sessionId : fixtureSessionIds) {
                sessionRegistry.removeSessionInformation(sessionId);
            }

            for (Long ownerId : fixtureOwnerIds) {
                jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",ownerId);
                jdbcTemplate.update("DELETE FROM app_users WHERE id=?",ownerId);
            }
        }
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,CELSIUS,20.0,,20.0,°C",
            "TEMPERATURE,FAHRENHEIT,20.0,,68.0,°F",
            "TEMPERATURE,KELVIN,20.0,,293.15,K",
            "HUMIDITY,KELVIN,45.5,,45.5,% RH",
            "MOTION,CELSIUS,,true,,''",
            "MOTION,CELSIUS,,false,,''"
    })
    void streamsPersistedReadingsWithTypedValuesStoredUnitsAndSensorLocalTimestamps(
            SensorType type,TemperatureUnit unit,Double numericValue,Boolean booleanValue,
            Double expectedNumericValue,String expectedSymbol) throws Exception {

        AppUser owner = persistUser(unit);
        Sensor sensor = persistSensor(owner,type);
        persistReading(sensor,numericValue,booleanValue,RECORDED_AT);
        MockHttpSession session = signIn(owner);

        MvcResult stream = openStream(sensor.getId(),session);

        assertThat(body(stream)).isEmpty();
        assertStreamStillOpen(stream);
        assertThat(stream.getRequest().getAsyncContext().getTimeout())
                .isEqualTo(policy.getConnectionLifetime().toMillis());

        executor.runAll();

        assertSseHeaders(stream);
        assertStreamStillOpen(stream);
        assertThat(stream.getResponse().isCommitted()).isTrue();

        List<SseFrame> frames = frames(stream);
        assertThat(frames).hasSize(1);

        JsonNode payload = readingPayload(frames.getFirst());
        assertThat(payload.path("sensorId").longValue()).isEqualTo(sensor.getId());
        assertThat(payload.path("readings").isArray()).isTrue();
        assertThat(payload.path("readings").size()).isEqualTo(1);

        JsonNode reading = payload.path("readings").path(0);
        assertThat(reading.path("sensorType").asString()).isEqualTo(type.name());
        assertThat(reading.path("installationLocation").asString()).isEqualTo(LOCATION);
        assertThat(reading.path("unitSymbol").asString()).isEqualTo(expectedSymbol);
        assertThat(reading.path("timestamp").asString()).isEqualTo("2026-01-15T14:59:00+03:00");
        assertThat(reading.path("timeZoneId").asString()).isEqualTo("Europe/Istanbul");
        assertThat(reading.path("offset").asString()).isEqualTo("+03:00");

        if (expectedNumericValue == null) {
            assertThat(reading.path("numericValue").isNull()).isTrue();
        } else {
            assertThat(reading.path("numericValue").doubleValue()).isCloseTo(expectedNumericValue,within(1.0e-9));
        }

        if (booleanValue == null) {
            assertThat(reading.path("booleanValue").isNull()).isTrue();
        } else {
            assertThat(reading.path("booleanValue").booleanValue()).isEqualTo(booleanValue);
        }

        String streamedBody = body(stream);
        MvcResult completed = expireConnection(stream);

        assertThat(body(completed)).isEqualTo(streamedBody);
        assertSseHeaders(completed);
    }



    @Test
    void streamsAnEmptySnapshotForAnOwnedSensorWithoutReadings() throws Exception {
        AppUser owner = persistUser(TemperatureUnit.CELSIUS);
        Sensor sensor = persistSensor(owner,SensorType.MOTION);
        MockHttpSession session = signIn(owner);

        MvcResult stream = openStream(sensor.getId(),session);
        executor.runAll();

        assertSseHeaders(stream);
        assertStreamStillOpen(stream);

        List<SseFrame> frames = frames(stream);
        assertThat(frames).hasSize(1);

        JsonNode payload = readingPayload(frames.getFirst());
        assertThat(payload.path("sensorId").longValue()).isEqualTo(sensor.getId());
        assertThat(payload.path("readings").isArray()).isTrue();
        assertThat(payload.path("readings").size()).isZero();
    }



    @Test
    void appendsChangedSnapshotsAndHeartbeatCommentsToTheSameOpenResponse() throws Exception {
        AppUser owner = persistUser(TemperatureUnit.CELSIUS);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE);
        persistReading(sensor,20.0,null,RECORDED_AT);
        MockHttpSession session = signIn(owner);

        MvcResult stream = openStream(sensor.getId(),session);
        executor.runAll();

        String initialBody = body(stream);

        persistReading(sensor,21.0,null,RECORDED_AT.plusSeconds(1));
        refreshAfter(policy.getRefreshInterval());

        List<SseFrame> changedFrames = frames(stream);
        assertThat(changedFrames).hasSize(2);
        assertThat(body(stream)).startsWith(initialBody);

        JsonNode initial = readingPayload(changedFrames.get(0));
        JsonNode changed = readingPayload(changedFrames.get(1));

        assertThat(initial.path("readings").size()).isEqualTo(1);
        assertThat(initial.at("/readings/0/numericValue").doubleValue()).isEqualTo(20.0);
        assertThat(changed.path("readings").size()).isEqualTo(2);
        assertThat(changed.at("/readings/0/numericValue").doubleValue()).isEqualTo(21.0);
        assertThat(changed.at("/readings/1/numericValue").doubleValue()).isEqualTo(20.0);

        String changedBody = body(stream);
        refreshAfter(policy.getRefreshInterval());

        assertThat(body(stream)).isEqualTo(changedBody);

        refreshAfter(policy.getHeartbeatInterval().minus(policy.getRefreshInterval()));

        List<SseFrame> heartbeatFrames = frames(stream);
        assertThat(heartbeatFrames).hasSize(3);

        SseFrame heartbeat = heartbeatFrames.getLast();
        assertThat(heartbeat.event()).isNull();
        assertThat(heartbeat.data()).isNull();
        assertThat(heartbeat.comments()).containsExactly("keepalive");
        assertThat(body(stream)).isEqualTo(changedBody + ":keepalive\n\n");
        assertStreamStillOpen(stream);

        String heartbeatBody = body(stream);
        refreshAfter(policy.getRefreshInterval());

        assertThat(body(stream)).isEqualTo(heartbeatBody);

        MvcResult completed = expireConnection(stream);
        assertThat(body(completed)).isEqualTo(heartbeatBody);
    }



    @Test
    void returnsTheSameNotFoundContractForForeignAndMissingSensors() throws Exception {
        AppUser requester = persistUser(TemperatureUnit.CELSIUS);
        AppUser otherOwner = persistUser(TemperatureUnit.CELSIUS);
        Sensor foreignSensor = persistSensor(otherOwner,SensorType.TEMPERATURE);
        Sensor deletedSensor = persistSensor(otherOwner,SensorType.TEMPERATURE);
        MockHttpSession session = signIn(requester);

        assertThat(jdbcTemplate.update("DELETE FROM sensors WHERE id=?",deletedSensor.getId())).isEqualTo(1);

        for (Long sensorId : List.of(foreignSensor.getId(),deletedSensor.getId())) {
            requestStream(sensorId,session)
                    .andExpect(status().isNotFound())
                    .andExpect(request().asyncNotStarted())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("SENSOR_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").value("The requested sensor was not found"));
        }

        assertThat(executor.pendingTasks()).isZero();
    }



    @ParameterizedTest
    @ValueSource(strings = {"unregistered","different-owner","different-principal","expired"})
    void requiresAnActiveRegistryEntryForTheAuthenticatedOwner(String registryState) throws Exception {
        AppUser owner = persistUser(TemperatureUnit.CELSIUS);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE);
        MockHttpSession session = signIn(owner);

        switch (registryState) {
            case "unregistered" -> sessionRegistry.removeSessionInformation(session.getId());
            case "different-owner" -> {
                AppUser otherOwner = persistUser(TemperatureUnit.CELSIUS);
                AuthenticatedUser otherPrincipal = new AuthenticatedUser(otherOwner);
                otherPrincipal.eraseCredentials();
                sessionRegistry.registerNewSession(session.getId(),otherPrincipal);
            }
            case "different-principal" ->
                    sessionRegistry.registerNewSession(session.getId(),owner.getEmail());
            case "expired" -> sessionRegistry.getSessionInformation(session.getId()).expireNow();
            default -> throw new IllegalArgumentException("Unknown registry state: " + registryState);
        }

        requestStream(sensor.getId(),session)
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        assertThat(executor.pendingTasks()).isZero();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void sessionDestructionSendsATerminalEventAndCompletesWithoutAnAuthenticatedSession(
            boolean sendInitialSnapshot) throws Exception {

        AppUser owner = persistUser(TemperatureUnit.CELSIUS);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE);
        persistReading(sensor,20.0,null,RECORDED_AT);
        MockHttpSession session = signIn(owner);

        MvcResult stream = openStream(sensor.getId(),session);

        if (sendInitialSnapshot) {
            executor.runAll();
        }

        String sessionId = session.getId();

        // MockHttpSession does not publish servlet lifecycle events automatically.
        eventPublisher.publishEvent(new HttpSessionDestroyedEvent(session));
        session.invalidate();
        stream.getRequest().removeAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME);

        assertThat(sessionRegistry.getSessionInformation(sessionId)).isNull();

        executor.runAll();

        List<SseFrame> frames = frames(stream);
        assertThat(frames).hasSize(sendInitialSnapshot ? 2 : 1);
        assertTerminal(frames.getLast(),"SESSION_EXPIRED");

        if (sendInitialSnapshot) {
            readingPayload(frames.getFirst());
        }

        String terminalBody = body(stream);
        MvcResult completed = finishStream(stream);

        handler().handlerType(SensorReadingStreamController.class).match(completed);
        assertThat(completed.getRequest().getSession(false)).isNull();
        assertThat(body(completed)).isEqualTo(terminalBody);
        assertSseHeaders(completed);
    }



    @Test
    void registryExpiryEndsAnAlreadyCommittedStreamWithAnSseTerminalEvent() throws Exception {
        AppUser owner = persistUser(TemperatureUnit.CELSIUS);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE);
        persistReading(sensor,20.0,null,RECORDED_AT);
        MockHttpSession session = signIn(owner);

        MvcResult stream = openStream(sensor.getId(),session);
        executor.runAll();

        assertThat(stream.getResponse().isCommitted()).isTrue();

        sessionRegistry.getSessionInformation(session.getId()).expireNow();
        streamService.maintainStreams();
        executor.runAll();

        List<SseFrame> frames = frames(stream);
        assertThat(frames).hasSize(2);
        readingPayload(frames.getFirst());
        assertTerminal(frames.getLast(),"SESSION_EXPIRED");

        String terminalBody = body(stream);
        MvcResult completed = finishStream(stream);

        assertThat(body(completed)).isEqualTo(terminalBody);
        assertSseHeaders(completed);
    }



    @Test
    void deletingTheSensorEndsItsOpenStreamWithTheSensorNotFoundReason() throws Exception {
        AppUser owner = persistUser(TemperatureUnit.CELSIUS);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE);
        persistReading(sensor,20.0,null,RECORDED_AT);
        MockHttpSession session = signIn(owner);

        MvcResult stream = openStream(sensor.getId(),session);
        executor.runAll();

        assertThat(jdbcTemplate.update("DELETE FROM sensors WHERE id=?",sensor.getId())).isEqualTo(1);

        refreshAfter(policy.getRefreshInterval());

        List<SseFrame> frames = frames(stream);
        assertThat(frames).hasSize(2);
        readingPayload(frames.getFirst());
        assertTerminal(frames.getLast(),"SENSOR_NOT_FOUND");

        String terminalBody = body(stream);
        MvcResult completed = finishStream(stream);

        assertThat(body(completed)).isEqualTo(terminalBody);
        assertSseHeaders(completed);

        requestStream(sensor.getId(),session)
                .andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("SENSOR_NOT_FOUND"));
    }



    @Test
    void reportsCapacityExhaustionAndAllowsAnotherConnectionAfterCompletion() throws Exception {
        AppUser owner = persistUser(TemperatureUnit.CELSIUS);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE);
        MockHttpSession session = signIn(owner);

        MvcResult first = openStream(sensor.getId(),session);
        executor.runAll();

        requestStream(sensor.getId(),session)
                .andExpect(status().isServiceUnavailable())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("STREAM_UNAVAILABLE"));

        expireConnection(first);

        MvcResult replacement = openStream(sensor.getId(),session);
        executor.runAll();

        assertSseHeaders(replacement);
        assertStreamStillOpen(replacement);
        assertThat(frames(replacement)).hasSize(1);
        readingPayload(frames(replacement).getFirst());
    }



    private ResultActions requestStream(Long sensorId,MockHttpSession session) throws Exception {
        ResultActions response = mockMvc.perform(get(STREAM_PATH,sensorId)
                .session(session)
                .accept(MediaType.TEXT_EVENT_STREAM));

        MvcResult result = response.andReturn();

        if (result.getRequest().isAsyncStarted()) {
            streams.add(result);
        }

        return response;
    }



    private MvcResult openStream(Long sensorId,MockHttpSession session) throws Exception {
        return requestStream(sensorId,session)
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }



    private MvcResult finishStream(MvcResult stream) throws Exception {
        assertThat(stream.getAsyncResult(1000)).isNull();

        return mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
    }



    private MvcResult expireConnection(MvcResult stream) throws Exception {
        nanoTime.advance(policy.getConnectionLifetime());
        streamService.maintainStreams();
        executor.runAll();
        return finishStream(stream);
    }



    private void refreshAfter(Duration duration) {
        nanoTime.advance(duration);
        streamService.maintainStreams();
        executor.runAll();
    }



    private void assertStreamStillOpen(MvcResult stream) {
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
        assertThat(WebAsyncUtils.getAsyncManager(stream.getRequest()).hasConcurrentResult()).isFalse();
    }



    private void assertSseHeaders(MvcResult stream) throws Exception {
        content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).match(stream);
        header().string(HttpHeaders.CACHE_CONTROL,"no-store, no-transform").match(stream);
        header().string("X-Accel-Buffering","no").match(stream);
    }



    private MockHttpSession signIn(AppUser owner) throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username",owner.getEmail())
                        .param("password",PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        fixtureSessionIds.add(session.getId());

        SessionInformation information = sessionRegistry.getSessionInformation(session.getId());
        assertThat(information).isNotNull();
        assertThat(information.isExpired()).isFalse();
        assertThat(information.getPrincipal()).isInstanceOf(AuthenticatedUser.class);

        AuthenticatedUser principal = (AuthenticatedUser) information.getPrincipal();
        assertThat(principal.getAppUserId()).isEqualTo(owner.getId());

        return session;
    }



    private AppUser persistUser(TemperatureUnit unit) {
        AppUser owner = new AppUser(
                "reading-stream-" + UUID.randomUUID() + "@example.com",passwordEncoder.encode(PASSWORD),
                PreferredLanguage.ENGLISH,unit,"Asia/Tokyo",CREATED_AT);
        owner.verifyEmail(CREATED_AT);

        AppUser saved = appUserRepository.saveAndFlush(owner);
        fixtureOwnerIds.add(saved.getId());
        return saved;
    }



    private Sensor persistSensor(AppUser owner,SensorType type) {
        Sensor sensor = new Sensor(
                owner,type,"Stream " + UUID.randomUUID(),"Istanbul","Kadikoy",
                LOCATION,"Europe/Istanbul",CREATED_AT);

        return sensorRepository.saveAndFlush(sensor);
    }



    private void persistReading(Sensor sensor,Double numericValue,Boolean booleanValue,Instant recordedAt) {
        SensorReading reading = switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,numericValue,recordedAt);
            case HUMIDITY -> SensorReading.humidity(sensor,numericValue,recordedAt);
            case MOTION -> SensorReading.motion(sensor,booleanValue,recordedAt);
        };

        readingRepository.saveAndFlush(reading);
    }



    private List<SseFrame> frames(MvcResult stream) {
        String responseBody = body(stream).replace("\r\n","\n");
        assertThat(responseBody).endsWith("\n\n");

        List<SseFrame> frames = new ArrayList<>();

        for (String block : responseBody.split("\n\n")) {
            String event = block.lines()
                    .filter(line -> line.startsWith("event:"))
                    .map(line -> line.substring(6).stripLeading())
                    .findFirst()
                    .orElse(null);

            String data = block.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).stripLeading())
                    .collect(Collectors.joining("\n"));

            List<String> comments = block.lines()
                    .filter(line -> line.startsWith(":"))
                    .map(line -> line.substring(1).stripLeading())
                    .toList();

            frames.add(new SseFrame(event,data.isEmpty() ? null : jsonMapper.readTree(data),comments));
        }

        return List.copyOf(frames);
    }



    private JsonNode readingPayload(SseFrame frame) {
        assertThat(frame.event()).isEqualTo("readings");
        assertThat(frame.comments()).isEmpty();
        assertThat(frame.data()).isNotNull();
        return frame.data();
    }



    private void assertTerminal(SseFrame frame,String code) {
        assertThat(frame.event()).isEqualTo("stream-ended");
        assertThat(frame.comments()).isEmpty();
        assertThat(frame.data()).isNotNull();
        assertThat(frame.data().size()).isEqualTo(1);
        assertThat(frame.data().path("code").asString()).isEqualTo(code);
    }



    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(),StandardCharsets.UTF_8);
    }



    private static ThreadPoolTaskExecutor streamExecutor() {
        return new ControlledStreamExecutor();
    }



    private static LongSupplier nanoTimeSource() {
        return new MutableNanoTime();
    }



    private record SseFrame(String event,JsonNode data,List<String> comments) {
    }



    private static final class MutableNanoTime implements LongSupplier {

        private final AtomicLong value = new AtomicLong();

        @Override
        public long getAsLong() {
            return value.get();
        }

        private void reset() {
            value.set(0);
        }

        private void advance(Duration duration) {
            value.addAndGet(duration.toNanos());
        }
    }
}