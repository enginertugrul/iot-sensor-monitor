package com.enginertugrul.iotsensormonitor.controller.advice;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.security.web.session.SimpleRedirectSessionInformationExpiredStrategy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;





@NullMarked
public final class ApiSecurityExceptionHandler implements AuthenticationEntryPoint,AccessDeniedHandler,SessionInformationExpiredStrategy {

    private final JsonMapper jsonMapper;
    private final RequestMatcher apiRequestMatcher;
    private final SessionInformationExpiredStrategy pageExpiredSessionStrategy =
            new SimpleRedirectSessionInformationExpiredStrategy("/login?sessionExpired");



    public ApiSecurityExceptionHandler(JsonMapper jsonMapper,RequestMatcher apiRequestMatcher) {
        this.jsonMapper = jsonMapper;
        this.apiRequestMatcher = apiRequestMatcher;
    }



    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ignoredException) throws IOException {
        writeProblem(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Authentication is required to access this resource"
        );
    }



    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ignoredException) throws IOException {
        writeProblem(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "Access to the requested resource is denied"
        );
    }



    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException,ServletException {
        HttpServletRequest request = event.getRequest();

        if (!apiRequestMatcher.matches(request)) {
            pageExpiredSessionStrategy.onExpiredSessionDetected(event);
            return;
        }

        writeProblem(
                request,
                event.getResponse(),
                HttpStatus.UNAUTHORIZED,
                "SESSION_EXPIRED",
                "The authenticated session has expired"
        );
    }



    private void writeProblem(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String detail
    ) throws IOException {

        ProblemDetail problemDetail = ApiProblemDetails.create(
                status,
                code,
                detail,
                request.getRequestURI()
        );

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL,CacheControl.noStore().getHeaderValue());
        jsonMapper.writeValue(response.getOutputStream(),problemDetail);
    }
}