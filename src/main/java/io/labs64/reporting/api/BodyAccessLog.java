package io.labs64.reporting.api;

import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.labs64.reporting.ReportingException;
import io.labs64.reporting.caller.Caller;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Bodies are the most sensitive thing this service returns, so every attempt to read one leaves a line saying who asked
 * for what, and how it ended. Never the body. The metric is per tenant and outcome, not per scope: a tenant's customers
 * would make the series unbounded.
 */
@Component
class BodyAccessLog {

    private static final Logger LOG = LoggerFactory.getLogger(BodyAccessLog.class);

    private final MeterRegistry meters;

    BodyAccessLog(MeterRegistry meters) {
        this.meters = meters;
    }

    <T> T record(Caller caller, UUID eventId, Supplier<T> read) {
        String outcome = "failed";
        try {
            T body = read.get();
            outcome = "read";
            return body;
        } catch (ReportingException e) {
            outcome = switch (e.status()) {
                case FORBIDDEN -> "forbidden";
                case NOT_FOUND -> "not_found";
                default -> "failed";
            };
            throw e;
        } finally {
            LOG.info("Event body access: tenant={} scope={} eventId={} outcome={}", caller.tenant(), caller.scope(), eventId,
                    outcome);
            meters.counter("reporting.body.access", "tenant", caller.tenant(), "outcome", outcome).increment();
        }
    }
}
