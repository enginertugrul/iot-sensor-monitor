package com.enginertugrul.iotsensormonitor.controller.advice;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;



final class ApiProblemDetails {

    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private ApiProblemDetails() {
    }



    static ProblemDetail create(HttpStatusCode statusCode,String code,String detail,String requestPath) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(statusCode,detail);
        HttpStatus resolvedStatus = HttpStatus.resolve(statusCode.value());

        problemDetail.setType(ABOUT_BLANK);
        problemDetail.setTitle(resolvedStatus == null ? "HTTP " + statusCode.value() : resolvedStatus.getReasonPhrase());
        problemDetail.setInstance(URI.create(requestPath));
        problemDetail.setProperty("code",code);
        problemDetail.setProperty("timestamp",Instant.now());

        return problemDetail;
    }



    static HttpHeaders headers(HttpHeaders sourceHeaders) {
        HttpHeaders headers = HttpHeaders.copyOf(sourceHeaders);
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.remove(HttpHeaders.CONTENT_DISPOSITION);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.setCacheControl(CacheControl.noStore());
        return headers;
    }
}