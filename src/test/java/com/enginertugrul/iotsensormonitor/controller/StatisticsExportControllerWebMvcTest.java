package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.config.SecurityConfig;
import com.enginertugrul.iotsensormonitor.controller.advice.ApiExceptionHandler;
import com.enginertugrul.iotsensormonitor.controller.advice.ApiMediaTypeExceptionHandler;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.security.EmailVerificationAuthenticationSuccessHandler;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.export.StatisticsCsvExport;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.export.StatisticsCsvExportService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.*;
import static com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit.FAHRENHEIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;




@WebMvcTest(controllers = StatisticsExportController.class,properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({SecurityConfig.class,EmailVerificationAuthenticationSuccessHandler.class,ApiExceptionHandler.class,ApiMediaTypeExceptionHandler.class})
class StatisticsExportControllerWebMvcTest {

    private static final Long OWNER_ID = 42L;
    private static final Long SENSOR_ID = 100L;
    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant END = Instant.parse("2026-01-15T11:00:00Z");
    private static final MediaType CSV_MEDIA_TYPE = new MediaType("text","csv",StandardCharsets.UTF_8);
    private static final String FILE_NAME = "sensor-100-statistics.csv";
    private static final String CSV_CONTENT = "sensor_name,average,unit\r\nÇalışma odası,68,°F\r\n";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StatisticsCsvExportService statisticsCsvExportService;

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



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"HOURLY","DAILY"})
    void bindsParametersAndDownloadsUtf8UsingPrincipalOwnershipAndStoredPreference(StatisticsResolution resolution) throws Exception {
        when(appUserService.getPreferredTemperatureUnit(OWNER_ID)).thenReturn(FAHRENHEIT);
        when(statisticsCsvExportService.createExport(SENSOR_ID,OWNER_ID,START,END,resolution,FAHRENHEIT))
                .thenReturn(new StatisticsCsvExport(FILE_NAME,CSV_CONTENT));

        MvcResult result = mockMvc.perform(request(
                        SENSOR_ID.toString(),"2026-01-15T13:00:00+03:00","2026-01-15T14:00:00+03:00",resolution.name())
                        .param("ownerId","999")
                        .param("temperatureUnit","KELVIN"))
                .andExpect(status().isOk())
                .andReturn();

        assertDownload(result);

        verify(appUserService).getPreferredTemperatureUnit(OWNER_ID);
        verify(statisticsCsvExportService).createExport(SENSOR_ID,OWNER_ID,START,END,resolution,FAHRENHEIT);
        verifyNoMoreInteractions(appUserService,statisticsCsvExportService);
    }



    @ParameterizedTest
    @NullAndEmptySource
    void defaultsMissingOrEmptyResolutionToAuto(String resolution) throws Exception {
        when(appUserService.getPreferredTemperatureUnit(OWNER_ID)).thenReturn(FAHRENHEIT);
        when(statisticsCsvExportService.createExport(SENSOR_ID,OWNER_ID,START,END,AUTO,FAHRENHEIT))
                .thenReturn(new StatisticsCsvExport(FILE_NAME,CSV_CONTENT));

        MvcResult result = mockMvc.perform(request(SENSOR_ID.toString(),START.toString(),END.toString(),resolution))
                .andExpect(status().isOk())
                .andReturn();

        assertDownload(result);

        verify(appUserService).getPreferredTemperatureUnit(OWNER_ID);
        verify(statisticsCsvExportService).createExport(SENSOR_ID,OWNER_ID,START,END,AUTO,FAHRENHEIT);
        verifyNoMoreInteractions(appUserService,statisticsCsvExportService);
    }



    @ParameterizedTest
    @MethodSource("invalidParameters")
    void rejectsInvalidParametersBeforeCallingApplicationServices(String sensorId,String start,String end,String resolution) throws Exception {
        mockMvc.perform(request(sensorId,start,end,resolution))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(appUserService,statisticsCsvExportService);
    }



    @Test
    void returnsBadRequestWhenTheServiceRejectsRawExport() throws Exception {
        when(appUserService.getPreferredTemperatureUnit(OWNER_ID)).thenReturn(FAHRENHEIT);
        when(statisticsCsvExportService.createExport(SENSOR_ID,OWNER_ID,START,END,RAW,FAHRENHEIT))
                .thenThrow(new InvalidStatisticsQueryException("Raw-reading CSV export is not available"));

        mockMvc.perform(request(SENSOR_ID.toString(),START.toString(),END.toString(),"RAW"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_STATISTICS_QUERY"));

        verify(appUserService).getPreferredTemperatureUnit(OWNER_ID);
        verify(statisticsCsvExportService).createExport(SENSOR_ID,OWNER_ID,START,END,RAW,FAHRENHEIT);
        verifyNoMoreInteractions(appUserService,statisticsCsvExportService);
    }



    @Test
    void returnsNotFoundWhenTheOwnerScopedLookupFails() throws Exception {
        when(appUserService.getPreferredTemperatureUnit(OWNER_ID)).thenReturn(FAHRENHEIT);
        when(statisticsCsvExportService.createExport(SENSOR_ID,OWNER_ID,START,END,HOURLY,FAHRENHEIT))
                .thenThrow(new SensorNotFoundException());

        mockMvc.perform(request(SENSOR_ID.toString(),START.toString(),END.toString(),"HOURLY"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SENSOR_NOT_FOUND"));

        verify(appUserService).getPreferredTemperatureUnit(OWNER_ID);
        verify(statisticsCsvExportService).createExport(SENSOR_ID,OWNER_ID,START,END,HOURLY,FAHRENHEIT);
        verifyNoMoreInteractions(appUserService,statisticsCsvExportService);
    }



    private MockHttpServletRequestBuilder request(String sensorId,String start,String end,String resolution) {
        MockHttpServletRequestBuilder request = get("/api/sensors/{sensorId}/statistics/export.csv",sensorId)
                .with(user(principal))
                .accept(CSV_MEDIA_TYPE,MediaType.APPLICATION_PROBLEM_JSON);

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



    private void assertDownload(MvcResult result) throws Exception {
        byte[] expectedBytes = CSV_CONTENT.getBytes(StandardCharsets.UTF_8);

        content().contentType(CSV_MEDIA_TYPE).match(result);
        content().bytes(expectedBytes).match(result);
        header().string(HttpHeaders.CONTENT_LENGTH,Integer.toString(expectedBytes.length)).match(result);
        header().string(HttpHeaders.CACHE_CONTROL,"no-store").match(result);
        header().string("X-Content-Type-Options","nosniff").match(result);

        String headerValue = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(headerValue).isNotBlank();
        ContentDisposition disposition = ContentDisposition.parse(headerValue);
        assertThat(disposition.getType()).isEqualTo("attachment");
        assertThat(disposition.getFilename()).isEqualTo(FILE_NAME);
    }



    private static Stream<Arguments> invalidParameters() {
        String sensorId = SENSOR_ID.toString();
        String start = START.toString();
        String end = END.toString();

        return Stream.of(
                arguments("not-a-number",start,end,"HOURLY"),
                arguments("9223372036854775808",start,end,"HOURLY"),
                arguments(sensorId,null,end,"HOURLY"),
                arguments(sensorId,start,null,"HOURLY"),
                arguments(sensorId,"not-an-instant",end,"HOURLY"),
                arguments(sensorId,start,"not-an-instant","HOURLY"),
                arguments(sensorId,start,end,"FORTNIGHTLY"));
    }
}