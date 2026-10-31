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
            // One line per request, no stack trace: an outage of the store makes every request fail, and a trace each
            // would flood the log. The cause's class and message say what failed; the trace stays at debug.
            Throwable cause = e.getCause();
            LOG.warn("Reporting unavailable: {}{}", e.getMessage(),
                    cause == null ? "" : " (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")");
            LOG.debug("Reporting unavailable, in full", e);
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
