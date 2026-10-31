package io.labs64.reporting.api;

import java.util.Map;
import java.util.function.Function;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.labs64.authcontext.core.AuthContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.labs64.reporting.ReportingService;
import io.labs64.reporting.caller.Caller;
import io.labs64.reporting.model.EventBody;
import io.labs64.reporting.model.EventPage;
import io.labs64.reporting.model.EventRef;
import io.labs64.reporting.model.EventSearch;
import io.labs64.reporting.model.MetricList;
import io.labs64.reporting.model.MetricQuery;
import io.labs64.reporting.model.MetricSeries;
import io.labs64.reporting.model.Status;
import io.labs64.reporting.model.TraceRef;

@RestController
public class ReportingController implements ReportingApi {

    private final ReportingService service;
    private final MeterRegistry meters;

    public ReportingController(ReportingService service, MeterRegistry meters) {
        this.service = service;
        this.meters = meters;
    }

    /** Timed per operation and tenant, never per scope: a tenant's customers would make the series unbounded. */
    private <T> ResponseEntity<T> timed(String operation, String scope, String groups, Integer historyDays,
            Function<Caller, T> call) {
        Caller caller = Caller.of(AuthContextHolder.require(), scope, groups, historyDays);
        Timer.Sample sample = Timer.start(meters);
        try {
            return ResponseEntity.ok(call.apply(caller));
        } finally {
            sample.stop(meters.timer("reporting.operation", "operation", operation, "tenant", caller.tenant()));
        }
    }

    @Override
    public ResponseEntity<MetricList> listMetrics(String scope, String groups, Integer historyDays) {
        return timed("listMetrics", scope, groups, historyDays, service::listMetrics);
    }

    @Override
    public ResponseEntity<MetricSeries> queryMetric(String scope, MetricQuery query, String groups,
            Integer historyDays) {
        return timed("queryMetric", scope, groups, historyDays, caller -> service.queryMetric(caller, query));
    }

    @Override
    public ResponseEntity<EventPage> searchEvents(String scope, EventSearch search, String groups,
            Integer historyDays) {
        return timed("searchEvents", scope, groups, historyDays, caller -> service.searchEvents(caller, search));
    }

    @Override
    public ResponseEntity<Map<String, Object>> getEvent(String scope, EventRef ref, String groups,
            Integer historyDays) {
        return timed("getEvent", scope, groups, historyDays, caller -> service.getEvent(caller, ref.getEventId()));
    }

    @Override
    public ResponseEntity<EventBody> getEventBody(String scope, EventRef ref, String groups, Integer historyDays) {
        return timed("getEventBody", scope, groups, historyDays, caller -> service.getBody(caller, ref.getEventId()));
    }

    @Override
    public ResponseEntity<EventPage> getTrace(String scope, TraceRef ref, String groups, Integer historyDays) {
        return timed("getTrace", scope, groups, historyDays, caller -> service.getTrace(caller, ref.getCorrelationId()));
    }

    @Override
    public ResponseEntity<Status> getStatus(String scope, String groups, Integer historyDays) {
        return timed("getStatus", scope, groups, historyDays, service::getStatus);
    }
}
