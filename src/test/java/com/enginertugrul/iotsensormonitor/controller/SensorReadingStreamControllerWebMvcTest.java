package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.config.SecurityConfig;
import com.enginertugrul.iotsensormonitor.controller.advice.ApiExceptionHandler;
import com.enginertugrul.iotsensormonitor.dto.reading.RecentSensorReadingsDTO;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.exception.SensorReadingStreamSessionExpiredException;
import com.enginertugrul.iotsensormonitor.exception.SensorReadingStreamUnavailableException;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.security.EmailVerificationAuthenticationSuccessHandler;
import com.enginertugrul.iotsensormonitor.service.reading.stream.SensorReadingStreamService;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



@WebMvcTest(
        controllers = SensorReadingStreamController.class,
        properties = "spring.config.location=classpath:/application-test.properties"
)
@ActiveProfiles("test")
@Import({SecurityConfig.class,EmailVerificationAuthenticationSuccessHandler.class,ApiExceptionHandler.class})
class SensorReadingStreamControllerWebMvcTest {

    private static final Long SENSOR_ID = 100L;
    private static final Long OWNER_ID = 42L;
    private static final String STREAM_PATH = "/api/sensors/{sensorId}/readings/stream";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private SensorReadingStreamService streamService;

    @MockitoBean
    private AppUserService appUserService;

    private MockHttpSession session;
    private SecurityContext securityContext;
    private SseEmitter openEmitter;
    private MvcResult openResult;



    @BeforeEach
    void setUp() {
        AppUser owner = user();
        ReflectionTestUtils.setField(owner,"id",OWNER_ID);
        owner.verifyEmail(CREATED_AT);

        AuthenticatedUser principal = new AuthenticatedUser(owner);
        principal.eraseCredentials();

        securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));

        session = new MockHttpSession(applicationContext.getServletContext());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,securityContext);
        sessionRegistry.registerNewSession(session.getId(),principal);
    }



    @AfterEach
    void tearDown() throws Exception {
        try {
            if (openEmitter != null) {
                openEmitter.complete();
            }

            if (openResult != null && openResult.getRequest().isAsyncStarted()) {
                openResult.getAsyncResult(1000);
                mockMvc.perform(asyncDispatch(openResult));
            }
        } finally {
            sessionRegistry.removeSessionInformation(session.getId());
        }
    }



    @Test
    void usesAuthenticatedOwnershipStoredPreferencesAndTheActualSessionForAnAsyncSseResponse() throws Exception {
        openEmitter = new SseEmitter(30_000L);

        when(appUserService.getPreferredTemperatureUnit(OWNER_ID)).thenReturn(TemperatureUnit.FAHRENHEIT);
        when(streamService.subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.FAHRENHEIT,session.getId()))
                .thenReturn(openEmitter);

        ResultActions response = mockMvc.perform(get(STREAM_PATH,SENSOR_ID)
                .session(session)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .param("ownerId","999")
                .param("temperatureUnit","KELVIN")
                .param("sessionId","forged-session"));

        openResult = response.andReturn();

        response.andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        assertThat(openResult.getRequest().getAsyncContext().getTimeout()).isEqualTo(30_000L);
        assertThat(WebAsyncUtils.getAsyncManager(openResult.getRequest()).hasConcurrentResult()).isFalse();
        assertThat(body(openResult)).isEmpty();

        RecentSensorReadingsDTO snapshot = new RecentSensorReadingsDTO(SENSOR_ID,List.of());
        openEmitter.send(SseEmitter.event().name("readings").data(snapshot,MediaType.APPLICATION_JSON));

        response.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store, no-transform"))
                .andExpect(header().string("X-Accel-Buffering","no"));

        String responseBody = body(openResult);
        String prefix = "event:readings\ndata:";

        assertThat(responseBody).startsWith(prefix).endsWith("\n\n");

        JsonNode payload = jsonMapper.readTree(responseBody.substring(prefix.length()).strip());
        assertThat(payload.path("sensorId").longValue()).isEqualTo(SENSOR_ID);
        assertThat(payload.path("readings").isArray()).isTrue();
        assertThat(payload.path("readings").size()).isZero();
        assertThat(WebAsyncUtils.getAsyncManager(openResult.getRequest()).hasConcurrentResult()).isFalse();

        openEmitter.complete();

        assertThat(openResult.getAsyncResult(1000)).isNull();

        MvcResult completed = mockMvc.perform(asyncDispatch(openResult))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andExpect(handler().handlerType(SensorReadingStreamController.class))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        assertThat(body(completed)).isEqualTo(responseBody);

        verify(appUserService).getPreferredTemperatureUnit(OWNER_ID);
        verify(streamService).subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.FAHRENHEIT,session.getId());
        verifyNoMoreInteractions(appUserService,streamService);
    }



    @Test
    void rejectsAnonymousRequestsBeforeCallingControllerCollaborators() throws Exception {
        mockMvc.perform(get(STREAM_PATH,SENSOR_ID).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        verifyNoInteractions(appUserService,streamService);
    }



    @Test
    void rejectsAnAuthenticatedRequestWithoutAnHttpSession() throws Exception {
        MvcResult result = mockMvc.perform(get(STREAM_PATH,SENSOR_ID)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .requestAttr(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME,securityContext))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        verifyNoInteractions(appUserService,streamService);
    }



    @Test
    void rejectsAnInvalidSensorIdBeforeCallingControllerCollaborators() throws Exception {
        mockMvc.perform(get(STREAM_PATH,"not-a-number")
                        .session(session)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(appUserService,streamService);
    }



    @ParameterizedTest
    @MethodSource("subscriptionFailures")
    void returnsTheStreamSpecificProblemBeforeAsyncProcessingStarts(
            RuntimeException failure,int expectedStatus,String expectedCode) throws Exception {

        when(appUserService.getPreferredTemperatureUnit(OWNER_ID)).thenReturn(TemperatureUnit.CELSIUS);
        when(streamService.subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS,session.getId()))
                .thenThrow(failure);

        mockMvc.perform(get(STREAM_PATH,SENSOR_ID)
                        .session(session)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().is(expectedStatus))
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(header().doesNotExist("X-Accel-Buffering"))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.instance").value("/api/sensors/100/readings/stream"));

        verify(appUserService).getPreferredTemperatureUnit(OWNER_ID);
        verify(streamService).subscribe(SENSOR_ID,OWNER_ID,TemperatureUnit.CELSIUS,session.getId());
        verifyNoMoreInteractions(appUserService,streamService);
    }



    private static Stream<Arguments> subscriptionFailures() {
        return Stream.of(
                arguments(new SensorNotFoundException(),404,"SENSOR_NOT_FOUND"),
                arguments(new SensorReadingStreamSessionExpiredException(),401,"SESSION_EXPIRED"),
                arguments(new SensorReadingStreamUnavailableException(),503,"STREAM_UNAVAILABLE")
        );
    }



    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(),StandardCharsets.UTF_8);
    }
}