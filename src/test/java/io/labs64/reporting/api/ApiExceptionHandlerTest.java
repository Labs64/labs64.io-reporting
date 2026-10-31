package io.labs64.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpConnectTimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.labs64.reporting.ReportingException;
import io.labs64.reporting.model.Error;

class ApiExceptionHandlerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private Level before;

    @BeforeEach
    void capture() {
        before = logger.getLevel();
        logger.setLevel(Level.INFO);
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void release() {
        logger.detachAppender(log);
        logger.setLevel(before);
    }

    @Test
    void anOutageIsOneLogLineWithoutAStackTraceAndAnswersWithRetryAfter() {
        ResponseEntity<Error> response = new ApiExceptionHandler().reporting(ReportingException
                .unavailable("The report store could not answer.", new HttpConnectTimeoutException("HTTP connect timed out")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(log.list).hasSize(1);
        ILoggingEvent line = log.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getThrowableProxy()).isNull();
        assertThat(line.getFormattedMessage()).contains("The report store could not answer.")
                .contains("HttpConnectTimeoutException: HTTP connect timed out");
    }

    @Test
    void anOutageWithoutACauseStillLogsOneLine() {
        new ApiExceptionHandler().reporting(ReportingException.unavailable("The report store could not answer.", null));

        assertThat(log.list).hasSize(1);
        assertThat(log.list.get(0).getFormattedMessage()).isEqualTo("Reporting unavailable: The report store could not answer.");
    }
}
