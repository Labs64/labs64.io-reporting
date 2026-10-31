package io.labs64.reporting.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.web.method.annotation.HandlerMethodValidationException;

import io.labs64.reporting.ReportingException;
import jakarta.validation.ConstraintViolationException;
import io.labs64.reporting.model.Error;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ReportingException.class)
    ResponseEntity<Error> reporting(ReportingException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        if (e.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            LOG.warn("Reporting unavailable: {}", e.getMessage(), e.getCause());
            response.header(HttpHeaders.RETRY_AFTER, "30");
        }
        return response.body(new Error().code(e.code()).message(e.getMessage()));
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class })
    ResponseEntity<Error> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new Error().code("INVALID_REQUEST").message("The request is invalid."));
    }
}
