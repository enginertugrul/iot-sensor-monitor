package com.enginertugrul.iotsensormonitor.controller.advice;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiMediaTypeExceptionHandler {



    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) throws HttpMediaTypeNotSupportedException {
        if (isHandledApiRequest(request)) {
            ProblemDetail problemDetail = ApiProblemDetails.create(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "The request media type is not supported",
                    request.getRequestURI()
            );

            return new ResponseEntity<>(
                    problemDetail,
                    ApiProblemDetails.headers(exception.getHeaders()),
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE
            );
        }

        throw exception;
    }




    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpServletRequest request
    ) throws HttpMediaTypeNotAcceptableException {
        if (isHandledApiRequest(request)) {
            ProblemDetail problemDetail = ApiProblemDetails.create(
                    HttpStatus.NOT_ACCEPTABLE,
                    "NOT_ACCEPTABLE",
                    "The requested response media type is not supported",
                    request.getRequestURI()
            );

            return new ResponseEntity<>(
                    problemDetail,
                    ApiProblemDetails.headers(exception.getHeaders()),
                    HttpStatus.NOT_ACCEPTABLE
            );
        }

        throw exception;
    }




    private boolean isHandledApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean statisticsRequest = path.startsWith("/api/sensors/") && path.contains("/statistics/");

        return statisticsRequest
                || path.equals("/readings/temperature")
                || path.equals("/readings/humidity")
                || path.equals("/readings/motion");
    }
}