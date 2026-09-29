package com.enginertugrul.iotsensormonitor.config;

import com.enginertugrul.iotsensormonitor.controller.SensorIngestionController;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.security.EmailVerificationAuthenticationSuccessHandler;
import com.enginertugrul.iotsensormonitor.service.reading.ingestion.SensorReadingIngestionService;
import com.enginertugrul.iotsensormonitor.testsupport.TestFixtures;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;



@WebMvcTest(controllers = {SensorIngestionController.class,SecurityConfigWebMvcTest.SecurityProbeController.class},
        properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({SecurityConfig.class,EmailVerificationAuthenticationSuccessHandler.class,
        SecurityConfigWebMvcTest.SecurityProbeController.class})
class SecurityConfigWebMvcTest {

    private static final String API_PATH = "/api/test/security";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionRegistry sessionRegistry;

    @MockitoBean
    private SensorReadingIngestionService ingestionService;

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
    @ValueSource(strings = {"application/json","text/html","text/event-stream"})
    void anonymousApiRequestsReceiveProblemsRegardlessOfAcceptHeader(String accept) throws Exception {
        assertSecurityProblem(mockMvc.perform(get(API_PATH).accept(accept)),
                401,"AUTHENTICATION_REQUIRED","Authentication is required to access this resource");
    }



    @ParameterizedTest
    @ValueSource(strings = {API_PATH,"/test/security"})
    void acceptsAuthenticatedReadsAndPostsWithValidCsrf(String path) throws Exception {
        mockMvc.perform(get(path).with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string("42"));

        mockMvc.perform(post(path).with(user(principal)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string("42"));
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rejectsMissingAndInvalidCsrfTokensOnAuthenticatedApiPosts(boolean invalidToken) throws Exception {
        MockHttpServletRequestBuilder request = post(API_PATH).with(user(principal));

        if (invalidToken) {
            request.with(csrf().useInvalidToken());
        }

        assertSecurityProblem(mockMvc.perform(request),
                403,"ACCESS_DENIED","Access to the requested resource is denied");
    }



    @Test
    void aValidCsrfTokenDoesNotAuthenticateAnAnonymousApiRequest() throws Exception {
        assertSecurityProblem(mockMvc.perform(post(API_PATH).with(csrf())),
                401,"AUTHENTICATION_REQUIRED","Authentication is required to access this resource");
    }



    @ParameterizedTest
    @CsvSource({
            "/readings/temperature,celsiusValue,21.5",
            "/readings/humidity,humidityPercentage,58.75",
            "/readings/motion,motionDetected,true"
    })
    void permitsTheThreeIngestionPostsWithoutLoginOrCsrf(String path,String field,String value) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .formField("sensorToken","test-only-token")
                        .formField(field,value)
                        .formField("recordedAt",Long.toString(TestFixtures.CREATED_AT.toEpochMilli())))
                .andExpect(status().isOk())
                .andExpect(handler().handlerType(SensorIngestionController.class))
                .andExpect(content().string(""));
    }



    @ParameterizedTest
    @CsvSource({
            "PUT,/readings/temperature",
            "PATCH,/readings/humidity",
            "DELETE,/readings/motion",
            "POST,/readings/temperature/extra",
            "POST,/readings/unrecognized",
            "POST,/test/security"
    })
    void ingestionCsrfExemptionsDoNotExtendToOtherMethodsOrPaths(String method,String path) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method),path).with(user(principal)))
                .andExpect(status().isForbidden());
    }



    @ParameterizedTest
    @ValueSource(strings = {"/readings/temperature","/readings/humidity","/readings/motion","/test/security"})
    void anonymousNonApiReadsUseTheLoginEntryPoint(String path) throws Exception {
        mockMvc.perform(get(path).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION,endsWith("/login")));
    }



    @ParameterizedTest
    @ValueSource(booleans = {true,false})
    void expiredRegisteredSessionsUseTheAppropriateApiOrPageResponse(boolean apiRequest) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String sessionId = session.getId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal,null,principal.getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);

        sessionRegistry.registerNewSession(sessionId,principal);
        sessionRegistry.getSessionInformation(sessionId).expireNow();

        try {
            String path = apiRequest ? API_PATH : "/test/security";
            ResultActions result = mockMvc.perform(get(path).session(session));

            if (apiRequest) {
                assertSecurityProblem(result,401,"SESSION_EXPIRED","The authenticated session has expired");
            } else {
                result.andExpect(status().is3xxRedirection())
                        .andExpect(header().string(HttpHeaders.LOCATION,endsWith("/login?sessionExpired")));
            }

            assertThat(session.isInvalid()).isTrue();
        } finally {
            sessionRegistry.removeSessionInformation(sessionId);
        }
    }



    @ParameterizedTest
    @CsvSource({
            "GET,/api/sensors/100/readings/stream,REQUEST,401",
            "GET,/api/sensors/100/readings/stream,ASYNC,200",
            "POST,/api/sensors/100/readings/stream,ASYNC,401",
            "GET,/api/test/dispatch,ASYNC,401",
            "GET,/api/test/dispatch,ERROR,200"
    })
    void appliesDispatcherExceptionsOnlyToTheirConfiguredScope(
            String method,String path,DispatcherType dispatcherType,int expectedStatus) throws Exception {

        ResultActions result = mockMvc.perform(request(HttpMethod.valueOf(method),path)
                        .with(csrf())
                        .with(request -> {
                            request.setDispatcherType(dispatcherType);
                            return request;
                        }))
                .andExpect(status().is(expectedStatus));

        if (expectedStatus == 200) {
            result.andExpect(content().string("dispatch"));
        } else {
            result.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }



    private void assertSecurityProblem(ResultActions result,int expectedStatus,String code,String detail) throws Exception {
        result.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value(API_PATH))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }



    @TestComponent
    @RestController
    static class SecurityProbeController {

        @RequestMapping(path = {API_PATH,"/test/security"},method = {RequestMethod.GET,RequestMethod.POST})
        public String authenticatedPrincipal(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
            return authenticatedUser.getAppUserId().toString();
        }

        @RequestMapping(path = {"/api/test/dispatch","/api/sensors/{sensorId}/readings/stream"},
                method = {RequestMethod.GET,RequestMethod.POST})
        public String dispatcherProbe() {
            return "dispatch";
        }
    }
}