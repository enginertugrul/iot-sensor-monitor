package com.enginertugrul.iotsensormonitor.controller.advice;

import com.enginertugrul.iotsensormonitor.config.SecurityConfig;
import com.enginertugrul.iotsensormonitor.controller.SensorIngestionController;
import com.enginertugrul.iotsensormonitor.controller.SensorReadingStreamController;
import com.enginertugrul.iotsensormonitor.controller.StatisticsExportController;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.security.EmailVerificationAuthenticationSuccessHandler;
import com.enginertugrul.iotsensormonitor.service.reading.ingestion.SensorReadingIngestionService;
import com.enginertugrul.iotsensormonitor.service.reading.statistics.export.StatisticsCsvExportService;
import com.enginertugrul.iotsensormonitor.service.reading.stream.SensorReadingStreamService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import com.enginertugrul.iotsensormonitor.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;



@WebMvcTest(controllers = {SensorIngestionController.class,StatisticsExportController.class,
        SensorReadingStreamController.class},
        properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({SecurityConfig.class,EmailVerificationAuthenticationSuccessHandler.class,
        ApiExceptionHandler.class,ApiMediaTypeExceptionHandler.class})
class ApiMediaTypeExceptionHandlerWebMvcTest {


    private static final String CONTEXT_PATH = "/monitor";
    private static final String SENSITIVE = "private-test-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiMediaTypeExceptionHandler advice;

    @MockitoBean
    private SensorReadingIngestionService ingestionService;

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
    }



    @ParameterizedTest
    @ValueSource(strings = {"/readings/temperature","/readings/humidity","/readings/motion"})
    void unsupportedIngestionContentTypesProduceProblemsUnderAContextPath(String path) throws Exception {
        String requestPath = CONTEXT_PATH + path;

        ResultActions result = mockMvc.perform(post(requestPath)
                .contextPath(CONTEXT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"sensorToken\":\"" + SENSITIVE + "\"}"));

        assertProblem(result,415,"UNSUPPORTED_MEDIA_TYPE","The request media type is not supported",requestPath);
        result.andExpect(header().string(HttpHeaders.ACCEPT,containsString(MediaType.APPLICATION_FORM_URLENCODED_VALUE)));

        verifyNoInteractions(ingestionService,exportService,streamService,appUserService);
    }



    @ParameterizedTest
    @CsvSource({
            "/api/sensors/100/statistics/export.csv,text/csv",
            "/api/sensors/100/readings/stream,text/event-stream"
    })
    void incompatibleAcceptHeadersProduceProblemsBeforeControllerInvocation(String path,String supportedType) throws Exception {
        String requestPath = CONTEXT_PATH + path;

        ResultActions result = mockMvc.perform(get(requestPath)
                .contextPath(CONTEXT_PATH)
                .with(user(principal))
                .queryParam("token",SENSITIVE)
                .accept(MediaType.APPLICATION_JSON));

        assertProblem(result,406,"NOT_ACCEPTABLE","The requested response media type is not supported",requestPath);
        result.andExpect(header().string(HttpHeaders.ACCEPT,containsString(supportedType)))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));

        verifyNoInteractions(ingestionService,exportService,streamService,appUserService);
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "/readings/temperature",
            "/api/sensors/100/statistics/series",
            "/api/sensors/100/readings/stream"
    })
    void doesNotRewriteCommittedResponsesForHandledPaths(String path) throws Exception {
        MockHttpServletRequest request = request(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write("already sent".getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();

        HttpMediaTypeNotSupportedException unsupported = new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_JSON,List.of(MediaType.APPLICATION_FORM_URLENCODED));
        HttpMediaTypeNotAcceptableException unacceptable = new HttpMediaTypeNotAcceptableException(
                List.of(MediaType.TEXT_EVENT_STREAM));

        assertThat(advice.handleUnsupportedMediaType(unsupported,request,response)).isNull();
        assertThat(advice.handleNotAcceptable(unacceptable,request,response)).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("already sent");
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "/settings",
            "/api/other",
            "/readings/temperature/extra",
            "/api/sensors/100/readings/stream/extra"
    })
    void rethrowsOriginalExceptionsOutsideTheHandledApiPaths(String path) {
        MockHttpServletRequest request = request(path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        HttpMediaTypeNotSupportedException unsupported = new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_JSON,List.of(MediaType.APPLICATION_FORM_URLENCODED));
        HttpMediaTypeNotAcceptableException unacceptable = new HttpMediaTypeNotAcceptableException(
                List.of(MediaType.TEXT_EVENT_STREAM));

        assertThatThrownBy(() -> advice.handleUnsupportedMediaType(unsupported,request,response))
                .isSameAs(unsupported);
        assertThatThrownBy(() -> advice.handleNotAcceptable(unacceptable,request,response))
                .isSameAs(unacceptable);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.isCommitted()).isFalse();
    }



    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",CONTEXT_PATH + path);
        request.setContextPath(CONTEXT_PATH);
        return request;
    }



    private void assertProblem(ResultActions result,int expectedStatus,String code,String detail,String path) throws Exception {
        result.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value(HttpStatus.valueOf(expectedStatus).getReasonPhrase()))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value(path))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());

        assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain(SENSITIVE);
    }
}