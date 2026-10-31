package io.labs64.reporting;

import org.springframework.http.HttpStatus;

/** A failure with the status and code the API answers; the message goes to the caller, so it holds no internals. */
public class ReportingException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ReportingException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ReportingException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ReportingException badRequest(String message) {
        return new ReportingException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public static ReportingException forbidden(String message) {
        return new ReportingException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ReportingException notFound(String message) {
        return new ReportingException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ReportingException unavailable(String message, Throwable cause) {
        return new ReportingException(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", message, cause);
    }
}
