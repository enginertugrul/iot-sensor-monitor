package com.enginertugrul.iotsensormonitor.controller.advice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;



class ApiSecurityExceptionHandlerTest {

    private static final String CONTEXT_PATH = "/monitor";
    private static final String API_PATH = "/api/sensors/100/statistics/series";
    private static final String SENSITIVE = "private-test-value";

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class,ProblemDetailJacksonMixin.class)
            .build();

    private final RequestMatcher apiMatcher = PathPatternRequestMatcher.pathPattern("/api/**");
    private final ApiSecurityExceptionHandler handler = new ApiSecurityExceptionHandler(jsonMapper,apiMatcher);



    @Test
    void authenticationFailuresUseASafeUnauthorizedProblem() throws Exception {
        MockHttpServletRequest request = request(API_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(request,response,new AuthenticationCredentialsNotFoundException(SENSITIVE));

        assertProblem(response,401,"AUTHENTICATION_REQUIRED",
                "Authentication is required to access this resource");
    }



    @Test
    void accessDenialsUseASafeForbiddenProblem() throws Exception {
        MockHttpServletRequest request = request(API_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request,response,new AccessDeniedException(SENSITIVE));

        assertProblem(response,403,"ACCESS_DENIED","Access to the requested resource is denied");
    }



    @Test
    void expiredApiSessionsUseAProblemUnderAContextPath() throws Exception {
        MockHttpServletRequest request = request(API_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onExpiredSessionDetected(expiredEvent(request,response));

        assertProblem(response,401,"SESSION_EXPIRED","The authenticated session has expired");
    }



    @Test
    void expiredPageSessionsRedirectWithinTheContextPath() throws Exception {
        MockHttpServletRequest request = request("/sensors");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onExpiredSessionDetected(expiredEvent(request,response));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/monitor/login?sessionExpired");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }



    @ParameterizedTest
    @ValueSource(strings = {"authentication","access-denied","session-expired"})
    void committedResponsesAreLeftIntactWithoutInvokingTheMapper(String operation) throws Exception {
        JsonMapper unusedMapper = mock(JsonMapper.class);
        ApiSecurityExceptionHandler guardedHandler = new ApiSecurityExceptionHandler(unusedMapper,apiMatcher);

        MockHttpServletRequest request = request(API_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(202);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store, no-transform");
        response.getOutputStream().write("event:readings\n\n".getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();

        switch (operation) {
            case "authentication" -> guardedHandler.commence(
                    request,response,new AuthenticationCredentialsNotFoundException(SENSITIVE));
            case "access-denied" -> guardedHandler.handle(request,response,new AccessDeniedException(SENSITIVE));
            case "session-expired" -> guardedHandler.onExpiredSessionDetected(expiredEvent(request,response));
            default -> throw new IllegalArgumentException("Unexpected test operation: " + operation);
        }

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store, no-transform");
        assertThat(response.getContentAsString()).isEqualTo("event:readings\n\n");
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
        verifyNoInteractions(unusedMapper);
    }



    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",CONTEXT_PATH + path);
        request.setContextPath(CONTEXT_PATH);
        request.setQueryString("token=" + SENSITIVE);
        return request;
    }



    private SessionInformationExpiredEvent expiredEvent(
            MockHttpServletRequest request,MockHttpServletResponse response) {

        SessionInformation information = new SessionInformation("test-principal","expired-session",new Date(0));
        information.expireNow();
        return new SessionInformationExpiredEvent(information,request,response);
    }



    private void assertProblem(MockHttpServletResponse response,int status,String code,String detail) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();

        String content = new String(response.getContentAsByteArray(),StandardCharsets.UTF_8);
        JsonNode body = jsonMapper.readTree(content);

        assertThat(body.size()).isEqualTo(7);
        assertThat(body.path("type").asString()).isEqualTo("about:blank");
        assertThat(body.path("title").asString()).isEqualTo(HttpStatus.valueOf(status).getReasonPhrase());
        assertThat(body.path("status").intValue()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("detail").asString()).isEqualTo(detail);
        assertThat(body.path("instance").asString()).isEqualTo(CONTEXT_PATH + API_PATH);
        assertThat(body.hasNonNull("timestamp")).isTrue();
        assertThat(body.has("properties")).isFalse();
        assertThat(body.has("exception")).isFalse();
        assertThat(body.has("trace")).isFalse();
        assertThat(content).doesNotContain(SENSITIVE,"AuthenticationCredentialsNotFoundException","AccessDeniedException");
    }
}