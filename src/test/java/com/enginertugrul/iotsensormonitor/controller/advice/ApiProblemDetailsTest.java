package com.enginertugrul.iotsensormonitor.controller.advice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;



class ApiProblemDetailsTest {



    @ParameterizedTest
    @CsvSource({
            "400,Bad Request",
            "401,Unauthorized",
            "404,Not Found",
            "500,Internal Server Error",
            "499,HTTP 499"
    })
    void createsStandardFieldsAndApplicationExtensions(int status,String title) {
        ProblemDetail problem = ApiProblemDetails.create(
                HttpStatusCode.valueOf(status),"TEST_PROBLEM","Safe detail","/monitor/api/sensors/100");

        assertThat(problem.getType()).isEqualTo(URI.create("about:blank"));
        assertThat(problem.getTitle()).isEqualTo(title);
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getDetail()).isEqualTo("Safe detail");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/monitor/api/sensors/100"));
        assertThat(problem.getProperties()).containsOnlyKeys("code","timestamp");
        assertThat(problem.getProperties()).containsEntry("code","TEST_PROBLEM");
        assertThat(problem.getProperties().get("timestamp")).isInstanceOf(Instant.class);
    }



    @Test
    void replacesRepresentationHeadersAndPreservesUsefulProtocolHeadersWithoutMutatingTheSource() {
        HttpHeaders source = new HttpHeaders();
        source.setContentType(MediaType.parseMediaType("text/csv"));
        source.setContentLength(512);
        source.set(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=readings.csv");
        source.setCacheControl("public, max-age=3600");
        source.set(HttpHeaders.ALLOW,"GET, POST");
        source.setAccept(List.of(MediaType.APPLICATION_FORM_URLENCODED));
        source.set(HttpHeaders.RETRY_AFTER,"60");
        source.add(HttpHeaders.VARY,"Accept-Encoding");

        HttpHeaders result = ApiProblemDetails.headers(HttpHeaders.readOnlyHttpHeaders(source));

        assertThat(result.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getCacheControl()).isEqualTo("no-store");
        assertThat(result.getFirst(HttpHeaders.CONTENT_LENGTH)).isNull();
        assertThat(result.getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(result.getFirst(HttpHeaders.ALLOW)).isEqualTo("GET, POST");
        assertThat(result.getAccept()).containsExactly(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat(result.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");

        assertThat(source.getContentType()).isEqualTo(MediaType.parseMediaType("text/csv"));
        assertThat(source.getContentLength()).isEqualTo(512);
        assertThat(source.getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=readings.csv");
        assertThat(source.getCacheControl()).isEqualTo("public, max-age=3600");

        result.add(HttpHeaders.VARY,"Origin");

        assertThat(result.get(HttpHeaders.VARY)).containsExactly("Accept-Encoding","Origin");
        assertThat(source.get(HttpHeaders.VARY)).containsExactly("Accept-Encoding");
    }



    @Test
    void emptySourceHeadersProduceIndependentMutableResults() {
        HttpHeaders first = ApiProblemDetails.headers(HttpHeaders.EMPTY);
        HttpHeaders second = ApiProblemDetails.headers(HttpHeaders.EMPTY);

        assertThat(first).isNotSameAs(second);
        assertThat(first.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(first.getCacheControl()).isEqualTo("no-store");

        first.setCacheControl("max-age=0");

        assertThat(second.getCacheControl()).isEqualTo("no-store");
        assertThat(HttpHeaders.EMPTY.getContentType()).isNull();
        assertThat(HttpHeaders.EMPTY.getCacheControl()).isNull();
    }
}