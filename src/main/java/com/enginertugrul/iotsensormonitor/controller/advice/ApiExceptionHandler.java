package com.enginertugrul.iotsensormonitor.controller.advice;

import com.enginertugrul.iotsensormonitor.controller.SensorIngestionController;
import com.enginertugrul.iotsensormonitor.controller.StatisticsApiController;
import com.enginertugrul.iotsensormonitor.controller.StatisticsExportController;
import com.enginertugrul.iotsensormonitor.exception.InactiveSensorException;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorTokenException;
import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;



@NullMarked
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RestControllerAdvice(assignableTypes = {SensorIngestionController.class,StatisticsApiController.class,StatisticsExportController.class})
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {




    @ExceptionHandler(InvalidStatisticsQueryException.class)
    public ResponseEntity<ProblemDetail> handleInvalidStatisticsQuery(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST,"INVALID_STATISTICS_QUERY","The statistics request is invalid",request);
    }



    @ExceptionHandler(InvalidSensorReadingException.class)
    public ResponseEntity<ProblemDetail> handleInvalidSensorReading(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST,"INVALID_SENSOR_READING","The sensor reading is invalid",request);
    }



    @ExceptionHandler(InvalidSensorTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidSensorToken(HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED,"INVALID_SENSOR_TOKEN","The sensor token is invalid",request);
    }



    @ExceptionHandler(SensorNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSensorNotFound(HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND,"SENSOR_NOT_FOUND","The requested sensor was not found",request);
    }



    @ExceptionHandler(InactiveSensorException.class)
    public ResponseEntity<ProblemDetail> handleInactiveSensor(HttpServletRequest request) {
        return response(HttpStatus.CONFLICT,"INACTIVE_SENSOR","The sensor is inactive",request);
    }



    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception,HttpServletRequest request) {
        logger.error("Unhandled API exception at " + request.getRequestURI(),exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","An unexpected error occurred",request);
    }



    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            logger.error("Unhandled API exception at " + requestPath(request),exception);
        }

        return super.handleExceptionInternal(exception,body,headers,statusCode,request);
    }



    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object ignoredBody,HttpHeaders headers,HttpStatusCode statusCode,WebRequest request) {
        ProblemDetail problemDetail = ApiProblemDetails.create(
                statusCode,
                frameworkCode(statusCode),
                frameworkDetail(statusCode),
                requestPath(request)
        );

        return new ResponseEntity<>(problemDetail,ApiProblemDetails.headers(headers),statusCode);
    }



    private ResponseEntity<ProblemDetail> response(HttpStatus status,String code,String detail,HttpServletRequest request) {
        ProblemDetail problemDetail = ApiProblemDetails.create(status,code,detail,request.getRequestURI());
        return new ResponseEntity<>(problemDetail,ApiProblemDetails.headers(HttpHeaders.EMPTY),status);
    }



    private String frameworkCode(HttpStatusCode statusCode) {
        if (statusCode.is5xxServerError()) {
            return "INTERNAL_ERROR";
        }

        return switch (statusCode.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "REQUEST_FAILED";
        };
    }



    private String frameworkDetail(HttpStatusCode statusCode) {
        if (statusCode.is5xxServerError()) {
            return "An unexpected error occurred";
        }

        return switch (statusCode.value()) {
            case 400 -> "The request is invalid";
            case 404 -> "The requested resource was not found";
            case 405 -> "The request method is not supported";
            case 406 -> "The requested response media type is not supported";
            case 413 -> "The request payload is too large";
            case 415 -> "The request media type is not supported";
            default -> "The request could not be completed";
        };
    }



    private String requestPath(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }

        return "/";
    }
}