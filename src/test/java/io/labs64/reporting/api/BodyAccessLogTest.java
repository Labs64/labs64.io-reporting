package io.labs64.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.labs64.reporting.ReportingException;
import io.labs64.reporting.caller.Caller;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BodyAccessLogTest {

    private static final Caller CALLER = new Caller("demo", "V1", Set.of("paid"), null);
    private static final UUID EVENT = UUID.fromString("0192f0aa-0000-7000-8000-000000000001");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BodyAccessLog log = new BodyAccessLog(meters);
    private final Logger logger = (Logger) LoggerFactory.getLogger(BodyAccessLog.class);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void capture() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void release() {
        logger.detachAppender(lines);
    }

    private double count(String outcome) {
        return meters.get("reporting.body.access").tag("tenant", "demo").tag("outcome", outcome).counter().count();
    }

    @Test
    void aReadSaysWhoAskedForWhatAndNeverTheBody() {
        assertThat(log.record(CALLER, EVENT, () -> "the secret body")).isEqualTo("the secret body");

        assertThat(count("read")).isEqualTo(1);
        String line = lines.list.get(0).getFormattedMessage();
        assertThat(line).contains("tenant=demo", "scope=V1", "eventId=" + EVENT, "outcome=read").doesNotContain("secret");
    }

    @Test
    void aMetricNeverCarriesTheScope() {
        log.record(CALLER, EVENT, () -> "x");
        assertThat(meters.get("reporting.body.access").counter().getId().getTags())
                .noneMatch(tag -> tag.getKey().equals("scope"));
    }

    @Test
    void aRefusalIsRecordedAndStillReachesTheCaller() {
        assertThatThrownBy(() -> log.record(CALLER, EVENT, () -> {
            throw ReportingException.forbidden("no");
        })).isInstanceOf(ReportingException.class);
        assertThatThrownBy(() -> log.record(CALLER, EVENT, () -> {
            throw ReportingException.notFound("no");
        })).isInstanceOf(ReportingException.class);
        assertThatThrownBy(() -> log.record(CALLER, EVENT, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("forbidden")).isEqualTo(1);
        assertThat(count("not_found")).isEqualTo(1);
        assertThat(count("failed")).isEqualTo(1);
    }
}
