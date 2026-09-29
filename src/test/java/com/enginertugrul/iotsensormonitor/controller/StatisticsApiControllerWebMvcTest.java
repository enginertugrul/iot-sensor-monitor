package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.config.SecurityConfig;
import com.enginertugrul.iotsensormonitor.controller.advice.ApiExceptionHandler;
import com.enginertugrul.iotsensormonitor.controller.advice.ApiMediaTypeExceptionHandler;
import com.enginertugrul.iotsensormonitor.dto.statistics.*;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.security.EmailVerificationAuthenticationSuccessHandler;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.StatisticsQueryService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.*;
import static com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit.FAHRENHEIT;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;




@WebMvcTest(controllers = StatisticsApiController.class,properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({SecurityConfig.class,EmailVerificationAuthenticationSuccessHandler.class,ApiExceptionHandler.class,ApiMediaTypeExceptionHandler.class})
class StatisticsApiControllerWebMvcTest {

    private static final Long OWNER_ID = 42L;
    private static final Long SENSOR_ID = 100L;
    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant END = Instant.parse("2026-01-15T11:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-01-15T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StatisticsQueryService statisticsQueryService;

    @MockitoBean
    private AppUserService appUserService;

    private AuthenticatedUser principal;



    @BeforeEach
    void setUp() {
        AppUser owner = new AppUser("statistics-owner@example.com","test-password-hash",START.minusSeconds(86400));
        ReflectionTestUtils.setField(owner,"id",OWNER_ID);
        owner.verifyEmail(START);
        principal = new AuthenticatedUser(owner);
        principal.eraseCredentials();
    }



    @Test
    void bindsOffsetTimestampsAndReturnsJsonUsingPrincipalOwnershipAndStoredPreference() throws Exception {
        when(appUserService.getPreferredTemperatureUnit(OWNER_ID)).thenReturn(FAHRENHEIT);
        when(statisticsQueryService.getSeries(SENSOR_ID,OWNER_ID,START,END,RAW,FAHRENHEIT))
                .thenReturn(series(RAW));

        mockMvc.perform(request(SENSOR_ID.toString(),"2026-01-15T13:00:00+03:00","2026-01-15T14:00:00+03:00","RAW")
                        .param("ownerId","999")
                        .param("temperatureUnit","KELVIN"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sensor.id").value(SENSOR_ID.intValue()))
                .andExpect(jsonPath("$.sensor.name").value("Çalışma odası"))
                .andExpect(jsonPath("$.sensor.type").value("TEMPERATURE"))
                .andExpect(jsonPath("$.sensor.timeZoneId").value("Europe/Istanbul"))
                .andExpect(jsonPath("$.sensor.canonicalUnit").value("C"))
                .andExpect(jsonPath("$.sensor.displayUnit").value("FAHRENHEIT"))
                .andExpect(jsonPath("$.sensor.displayUnitSymbol").value("°F"))
                .andExpect(jsonPath("$.requestedStartInclusive").value(START.toString()))
                .andExpect(jsonPath("$.requestedEndExclusive").value(END.toString()))
                .andExpect(jsonPath("$.evaluatedStartInclusive").value(START.toString()))
                .andExpect(jsonPath("$.evaluatedEndExclusive").value(END.toString()))
                .andExpect(jsonPath("$.asOf").value(AS_OF.toString()))
                .andExpect(jsonPath("$.requestedResolution").value("RAW"))
                .andExpect(jsonPath("$.resolvedResolution").value("RAW"))
                .andExpect(jsonPath("$.displayGranularity").value("RAW"))
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.fullyCovered").value(true))
                .andExpect(jsonPath("$.conditions.containsExpiredIntervals").value(false))
                .andExpect(jsonPath("$.conditions.containsRollupDelayedIntervals").value(false))
                .andExpect(jsonPath("$.conditions.containsIncompleteIntervals").value(false))
                .andExpect(jsonPath("$.pointBudget").value(400))
                .andExpect(jsonPath("$.csvExport.available").value(false))
                .andExpect(jsonPath("$.csvExport.rowCount").value(0))
                .andExpect(jsonPath("$.csvExport.rowLimit").value(2500))
                .andExpect(jsonPath("$.coverage.raw.resolution").value("RAW"))
                .andExpect(jsonPath("$.coverage.raw.rollupProgress").value(nullValue()))
                .andExpect(jsonPath("$.coverage.hourly.rollupProgress.safeThroughExclusive").value(END.toString()))
                .andExpect(jsonPath("$.coverage.daily.resolution").value("DAILY"))
                .andExpect(jsonPath("$.periodMetrics.available").value(true))
                .andExpect(jsonPath("$.periodMetrics.sourceSampleCount").value(1))
                .andExpect(jsonPath("$.periodMetrics.numericMetrics.sum").value(68))
                .andExpect(jsonPath("$.periodMetrics.numericMetrics.minimum").value(68))
                .andExpect(jsonPath("$.periodMetrics.numericMetrics.average").value(68))
                .andExpect(jsonPath("$.periodMetrics.numericMetrics.maximum").value(68))
                .andExpect(jsonPath("$.periodMetrics.motionMetrics").value(nullValue()))
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points.length()").value(1))
                .andExpect(jsonPath("$.points[0].granularity").value("RAW"))
                .andExpect(jsonPath("$.points[0].sourceReadingId").value(501))
                .andExpect(jsonPath("$.points[0].recordedAt").value(START.toString()))
                .andExpect(jsonPath("$.points[0].bucketStart").value(nullValue()))
                .andExpect(jsonPath("$.points[0].bucketEnd").value(nullValue()))
                .andExpect(jsonPath("$.points[0].status").value("COMPLETE"))
                .andExpect(jsonPath("$.points[0].sourceSampleCount").value(1))
                .andExpect(jsonPath("$.points[0].numericMetrics.average").value(68))
                .andExpect(jsonPath("$.points[0].motionMetrics").value(nullValue()));

        verify(appUserService).getPreferredTemperatureUnit(OWNER_ID);
        verify(statisticsQueryService).getSeries(SENSOR_ID,OWNER_ID,START,END,RAW,FAHRENHEIT);
        verifyNoMoreInteractions(appUserService,statisticsQueryService);
    }



    @ParameterizedTest
    @NullAndEmptySource
    void defaultsMissingOrEmptyResolutionToAuto(String resolution) throws Exception {
        when(appUserService.getPreferredTemperatureUnit(OWNER_ID)).thenReturn(FAHRENHEIT);
        when(statisticsQueryService.getSeries(SENSOR_ID,OWNER_ID,START,END,AUTO,FAHRENHEIT))
                .thenReturn(series(AUTO));

        mockMvc.perform(request(SENSOR_ID.toString(),START.toString(),END.toString(),resolution))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedResolution").value("AUTO"))
                .andExpect(jsonPath("$.resolvedResolution").value("RAW"));

        verify(appUserService).getPreferredTemperatureUnit(OWNER_ID);
        verify(statisticsQueryService).getSeries(SENSOR_ID,OWNER_ID,START,END,AUTO,FAHRENHEIT);
        verifyNoMoreInteractions(appUserService,statisticsQueryService);
    }



    @ParameterizedTest
    @MethodSource("invalidParameters")
    void rejectsInvalidParametersBeforeCallingApplicationServices(String sensorId,String start,String end,String resolution) throws Exception {
        mockMvc.perform(request(sensorId,start,end,resolution))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(appUserService,statisticsQueryService);
    }



    @Test
    void returnsNotFoundWhenTheOwnerScopedLookupFails() throws Exception {
        when(appUserService.getPreferredTemperatureUnit(OWNER_ID)).thenReturn(FAHRENHEIT);
        when(statisticsQueryService.getSeries(SENSOR_ID,OWNER_ID,START,END,RAW,FAHRENHEIT))
                .thenThrow(new SensorNotFoundException());

        mockMvc.perform(request(SENSOR_ID.toString(),START.toString(),END.toString(),"RAW"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SENSOR_NOT_FOUND"));

        verify(appUserService).getPreferredTemperatureUnit(OWNER_ID);
        verify(statisticsQueryService).getSeries(SENSOR_ID,OWNER_ID,START,END,RAW,FAHRENHEIT);
        verifyNoMoreInteractions(appUserService,statisticsQueryService);
    }



    private MockHttpServletRequestBuilder request(String sensorId,String start,String end,String resolution) {
        MockHttpServletRequestBuilder request = get("/api/sensors/{sensorId}/statistics/series",sensorId)
                .with(user(principal))
                .accept(MediaType.APPLICATION_JSON,MediaType.APPLICATION_PROBLEM_JSON);

        if (start != null) {
            request.param("startInclusive",start);
        }
        if (end != null) {
            request.param("endExclusive",end);
        }
        if (resolution != null) {
            request.param("resolution",resolution);
        }
        return request;
    }



    private static Stream<Arguments> invalidParameters() {
        String sensorId = SENSOR_ID.toString();
        String start = START.toString();
        String end = END.toString();

        return Stream.of(
                arguments("not-a-number",start,end,"RAW"),
                arguments("9223372036854775808",start,end,"RAW"),
                arguments(sensorId,null,end,"RAW"),
                arguments(sensorId,start,null,"RAW"),
                arguments(sensorId,"not-an-instant",end,"RAW"),
                arguments(sensorId,start,"not-an-instant","RAW"),
                arguments(sensorId,start,end,"FORTNIGHTLY"));
    }



    private static SensorStatisticsSeriesDTO series(StatisticsResolution requestedResolution) {
        StatisticsSensorDTO sensor = new StatisticsSensorDTO(
                SENSOR_ID,"Çalışma odası",SensorType.TEMPERATURE,"Europe/Istanbul",MeasurementUnit.C,"FAHRENHEIT","°F");
        BigDecimal value = BigDecimal.valueOf(68);
        StatisticsNumericMetricsDTO metrics = new StatisticsNumericMetricsDTO(value,value,value,value);
        StatisticsSeriesPointDTO point = new StatisticsSeriesPointDTO(
                StatisticsDisplayGranularity.RAW,501L,START,null,null,null,null,null,
                StatisticsPointStatus.COMPLETE,1L,metrics,null,null,null);

        StatisticsTierCoverageDTO raw = new StatisticsTierCoverageDTO(
                RAW,AS_OF.minusSeconds(30L * 86400),START,AS_OF,null);
        StatisticsTierCoverageDTO hourly = new StatisticsTierCoverageDTO(
                HOURLY,AS_OF.minusSeconds(90L * 86400),START,END,
                new StatisticsRollupProgressDTO(START,END,END,0,false));
        StatisticsTierCoverageDTO daily = new StatisticsTierCoverageDTO(
                DAILY,AS_OF.minusSeconds(730L * 86400),null,null,
                new StatisticsRollupProgressDTO(null,null,Instant.parse("2026-01-14T21:00:00Z"),0,false));

        return new SensorStatisticsSeriesDTO(
                sensor,START,END,START,END,AS_OF,requestedResolution,RAW,StatisticsDisplayGranularity.RAW,
                StatisticsRangeStatus.COMPLETE,new StatisticsRangeConditionsDTO(false,false,false),true,400,
                new StatisticsCsvExportAvailabilityDTO(false,0,2500),new StatisticsCoverageDTO(raw,hourly,daily),
                new StatisticsPeriodMetricsDTO(true,1,metrics,null),List.of(point));
    }
}