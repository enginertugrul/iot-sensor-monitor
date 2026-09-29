package com.enginertugrul.iotsensormonitor.controller.advice;

import com.enginertugrul.iotsensormonitor.config.SecurityConfig;
import com.enginertugrul.iotsensormonitor.controller.SensorIngestionController;
import com.enginertugrul.iotsensormonitor.controller.SensorReadingStreamController;
import com.enginertugrul.iotsensormonitor.controller.StatisticsApiController;
import com.enginertugrul.iotsensormonitor.controller.StatisticsExportController;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.*;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.security.EmailVerificationAuthenticationSuccessHandler;
import com.enginertugrul.iotsensormonitor.service.reading.ingestion.SensorReadingIngestionService;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.StatisticsQueryService;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.export.StatisticsCsvExportService;
import com.enginertugrul.iotsensormonitor.service.reading.stream.SensorReadingStreamService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import com.enginertugrul.iotsensormonitor.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.AUTO;
import static com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit.CELSIUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;



@WebMvcTest(controllers = {SensorIngestionController.class,StatisticsApiController.class,
        StatisticsExportController.class,SensorReadingStreamController.class},
        properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({SecurityConfig.class,EmailVerificationAuthenticationSuccessHandler.class,
        ApiExceptionHandler.class,ApiMediaTypeExceptionHandler.class})
class ApiExceptionHandlerWebMvcTest {


    private static final String INGESTION_PATH = "/readings/temperature";
    private static final String SERIES_PATH = "/api/sensors/100/statistics/series";
    private static final String EXPORT_PATH = "/api/sensors/100/statistics/export.csv";
    private static final String STREAM_PATH = "/api/sensors/100/readings/stream";
    private static final String SENSITIVE = "private-test-value";
    private static final Instant START = TestFixtures.CREATED_AT.minusSeconds(3600);
    private static final Instant END = TestFixtures.CREATED_AT;
    private static final Instant RECORDED_AT = END.minusSeconds(60);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiExceptionHandler advice;

    @MockitoBean
    private SensorReadingIngestionService ingestionService;

    @MockitoBean
    private StatisticsQueryService statisticsQueryService;

    @MockitoBean
    private StatisticsCsvExportService exportService;

    @MockitoBean
    private SensorReadingStreamService streamService;

    @MockitoBean
    private AppUserService appUserService;

    private AuthenticatedUser principal;



    @BeforeEach
    void setUp() {
        AppUser owner = TestFixtures.user();
        ReflectionTestUtils.setField(owner,"id",42L);
        owner.verifyEmail(TestFixtures.CREATED_AT);
        principal = new AuthenticatedUser(owner);
        principal.eraseCredentials();

        when(appUserService.getPreferredTemperatureUnit(42L)).thenReturn(CELSIUS);
    }



    @ParameterizedTest
    @MethodSource("domainFailures")
    void rendersDomainFailuresAsSafeProblems(
            String path,RuntimeException failure,int expectedStatus,String code,String detail) throws Exception {

        MockHttpServletRequestBuilder request;

        switch (path) {
            case INGESTION_PATH -> {
                doThrow(failure).when(ingestionService).ingestTemperature(SENSITIVE,21.5,RECORDED_AT);
                request = ingestionRequest(SENSITIVE,"21.5");
            }
            case SERIES_PATH -> {
                when(statisticsQueryService.getSeries(100L,42L,START,END,AUTO,CELSIUS)).thenThrow(failure);
                request = statisticsRequest(path);
            }
            case STREAM_PATH -> {
                MockHttpSession session = new MockHttpSession();
                when(streamService.subscribe(100L,42L,CELSIUS,session.getId())).thenThrow(failure);

                request = get(path).session(session).with(user(principal))
                        .queryParam("token",SENSITIVE)
                        .accept(MediaType.TEXT_EVENT_STREAM,MediaType.APPLICATION_PROBLEM_JSON);
            }
            default -> throw new IllegalArgumentException("Unexpected test endpoint: " + path);
        }

        assertProblem(mockMvc.perform(request),expectedStatus,code,detail,path);
    }



    @ParameterizedTest
    @MethodSource("unexpectedFailures")
    void unexpectedExportFailuresProduceSafeProblemsWithoutDownloadHeaders(RuntimeException failure) throws Exception {
        when(exportService.createExport(100L,42L,START,END,AUTO,CELSIUS)).thenThrow(failure);

        ResultActions result = mockMvc.perform(statisticsRequest(EXPORT_PATH)
                .accept(MediaType.parseMediaType("text/csv"),MediaType.APPLICATION_PROBLEM_JSON));

        assertProblem(result,500,"INTERNAL_ERROR","An unexpected error occurred",EXPORT_PATH);
        result.andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void missingAndMalformedParametersDoNotExposeBindingDetails(boolean malformed) throws Exception {
        MockHttpServletRequestBuilder request = get(SERIES_PATH)
                .with(user(principal))
                .param("endExclusive",END.toString())
                .queryParam("token",SENSITIVE)
                .accept(MediaType.APPLICATION_PROBLEM_JSON);

        if (malformed) {
            request.param("startInclusive",SENSITIVE);
        }

        assertProblem(mockMvc.perform(request),400,"INVALID_REQUEST","The request is invalid",SERIES_PATH);
        verifyNoInteractions(appUserService,statisticsQueryService);
    }



    @Test
    void invalidFormValuesAreNotIncludedInTheProblemBody() throws Exception {
        assertProblem(mockMvc.perform(ingestionRequest(SENSITIVE.repeat(30),SENSITIVE)),
                400,"INVALID_REQUEST","The request is invalid",INGESTION_PATH);

        verifyNoInteractions(ingestionService);
    }



    @ParameterizedTest
    @CsvSource({
            "400,INVALID_REQUEST,The request is invalid",
            "404,NOT_FOUND,The requested resource was not found",
            "405,METHOD_NOT_ALLOWED,The request method is not supported",
            "406,NOT_ACCEPTABLE,The requested response media type is not supported",
            "413,PAYLOAD_TOO_LARGE,The request payload is too large",
            "415,UNSUPPORTED_MEDIA_TYPE,The request media type is not supported",
            "422,REQUEST_FAILED,The request could not be completed",
            "500,INTERNAL_ERROR,An unexpected error occurred",
            "503,INTERNAL_ERROR,An unexpected error occurred"
    })
    void mapsFrameworkStatusesAndReplacesUnsafeBodies(int status,String code,String detail) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",SERIES_PATH);
        request.setQueryString("token=" + SENSITIVE);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ALLOW,"GET");
        headers.setContentLength(100);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=private.csv");

        ResponseEntity<Object> result = advice.createResponseEntity(
                SENSITIVE,headers,HttpStatusCode.valueOf(status),new ServletWebRequest(request));

        assertThat(result.getStatusCode().value()).isEqualTo(status);
        assertThat(result.getBody()).isInstanceOfSatisfying(ProblemDetail.class,problem -> {
            assertThat(problem.getStatus()).isEqualTo(status);
            assertThat(problem.getDetail()).isEqualTo(detail);
            assertThat(problem.getInstance()).isEqualTo(URI.create(SERIES_PATH));
            assertThat(problem.getProperties()).containsEntry("code",code);
        });

        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(result.getHeaders().getFirst(HttpHeaders.ALLOW)).isEqualTo("GET");
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)).isNull();
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }



    @Test
    void usesTheRootInstanceForANonServletWebRequest() {
        ResponseEntity<Object> result = advice.createResponseEntity(
                SENSITIVE,HttpHeaders.EMPTY,HttpStatus.BAD_REQUEST,mock(WebRequest.class));

        assertThat(result.getBody()).isInstanceOfSatisfying(ProblemDetail.class,problem ->
                assertThat(problem.getInstance()).isEqualTo(URI.create("/")));
    }



    @Test
    void doesNotReplaceAnAlreadyCommittedResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",STREAM_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.getOutputStream().write("event:readings\n\n".getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();

        IllegalStateException failure = new IllegalStateException(SENSITIVE);

        assertThat(advice.handleUnexpectedException(failure,request,response)).isNull();
        assertThat(advice.handleExceptionInternal(failure,SENSITIVE,HttpHeaders.EMPTY,
                HttpStatus.INTERNAL_SERVER_ERROR,new ServletWebRequest(request,response))).isNull();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getContentAsString()).isEqualTo("event:readings\n\n");
    }



    @ParameterizedTest
    @MethodSource("disconnects")
    void disconnectedClientsDoNotTriggerAnotherResponse(Exception failure) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",STREAM_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(advice.handleUnexpectedException(failure,request,response)).isNull();
        assertThat(advice.handleExceptionInternal(failure,SENSITIVE,HttpHeaders.EMPTY,
                HttpStatus.INTERNAL_SERVER_ERROR,new ServletWebRequest(request,response))).isNull();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.isCommitted()).isFalse();
    }



    private MockHttpServletRequestBuilder ingestionRequest(String token,String value) {
        return post(INGESTION_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_PROBLEM_JSON)
                .queryParam("debug",SENSITIVE)
                .formField("sensorToken",token)
                .formField("celsiusValue",value)
                .formField("recordedAt",Long.toString(RECORDED_AT.toEpochMilli()));
    }



    private MockHttpServletRequestBuilder statisticsRequest(String path) {
        return get(path).with(user(principal))
                .param("startInclusive",START.toString())
                .param("endExclusive",END.toString())
                .queryParam("token",SENSITIVE)
                .accept(MediaType.APPLICATION_JSON,MediaType.APPLICATION_PROBLEM_JSON);
    }



    private void assertProblem(ResultActions result,int expectedStatus,String code,String detail,String path) throws Exception {
        result.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value(HttpStatus.valueOf(expectedStatus).getReasonPhrase()))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value(path))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.properties").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(result.andReturn().getResponse().getContentAsString())
                .doesNotContain(SENSITIVE,"java.lang.","com.enginertugrul");
    }



    private static Stream<Arguments> domainFailures() {
        return Stream.of(
                arguments(INGESTION_PATH,new InvalidSensorReadingException(SENSITIVE),
                        400,"INVALID_SENSOR_READING","The sensor reading is invalid"),
                arguments(INGESTION_PATH,new InvalidSensorTokenException(),
                        401,"INVALID_SENSOR_TOKEN","The sensor token is invalid"),
                arguments(INGESTION_PATH,new InactiveSensorException(),
                        409,"INACTIVE_SENSOR","The sensor is inactive"),
                arguments(SERIES_PATH,new InvalidStatisticsQueryException(SENSITIVE),
                        400,"INVALID_STATISTICS_QUERY","The statistics request is invalid"),
                arguments(SERIES_PATH,new SensorNotFoundException(),
                        404,"SENSOR_NOT_FOUND","The requested sensor was not found"),
                arguments(STREAM_PATH,new SensorReadingStreamSessionExpiredException(),
                        401,"SESSION_EXPIRED","The authenticated session has expired"),
                arguments(STREAM_PATH,new SensorReadingStreamUnavailableException(),
                        503,"STREAM_UNAVAILABLE","Recent reading streaming is temporarily unavailable")
        );
    }



    private static Stream<RuntimeException> unexpectedFailures() {
        return Stream.of(
                new IllegalStateException(SENSITIVE),
                new DataAccessResourceFailureException(SENSITIVE,new IOException("Connection reset by peer"))
        );
    }



    private static Stream<Exception> disconnects() {
        return Stream.of(
                new EOFException(),
                new IOException("Broken pipe"),
                new IllegalStateException("Response failed",new IOException("Connection reset by peer"))
        );
    }
}