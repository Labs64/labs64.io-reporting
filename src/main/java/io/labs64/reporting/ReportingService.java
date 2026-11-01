package io.labs64.reporting;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import io.labs64.reporting.body.BodyStore;
import io.labs64.reporting.caller.Caller;
import io.labs64.reporting.catalogue.Catalogue;
import io.labs64.reporting.catalogue.Catalogue.EventSource;
import io.labs64.reporting.catalogue.Catalogue.Metric;
import io.labs64.reporting.catalogue.CatalogueRegistry;
import io.labs64.reporting.clickhouse.ClickHouseClient;
import io.labs64.reporting.model.EventBody;
import io.labs64.reporting.model.EventPage;
import io.labs64.reporting.model.EventSearch;
import io.labs64.reporting.model.MetricInfo;
import io.labs64.reporting.model.MetricList;
import io.labs64.reporting.model.MetricPoint;
import io.labs64.reporting.model.MetricQuery;
import io.labs64.reporting.model.MetricSeries;
import io.labs64.reporting.model.Status;
import io.labs64.reporting.query.Cursor;
import io.labs64.reporting.query.QueryBuilder;

@Service
public class ReportingService {

    static final int MAX_TRACE_EVENTS = 200;

    /** ClickHouse's text for a time, with or without a time of day and fractions. */
    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2299;

    private static final DateTimeFormatter STORED_TIME = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd")
            .optionalStart().appendPattern(" HH:mm:ss").optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd().optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0).parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0).toFormatter();

    private final CatalogueRegistry catalogues;
    private final ClickHouseClient clickHouse;
    private final BodyStore bodies;
    private final Clock clock;

    @Autowired
    public ReportingService(CatalogueRegistry catalogues, ClickHouseClient clickHouse, BodyStore bodies) {
        this(catalogues, clickHouse, bodies, Clock.systemUTC());
    }

    ReportingService(CatalogueRegistry catalogues, ClickHouseClient clickHouse, BodyStore bodies, Clock clock) {
        this.catalogues = catalogues;
        this.clickHouse = clickHouse;
        this.bodies = bodies;
        this.clock = clock;
    }

    public MetricList listMetrics(Caller caller) {
        List<MetricInfo> metrics = catalogue(caller).metrics().values().stream()
                .filter(m -> caller.groups().contains(m.group())).sorted(Comparator.comparing(Metric::name))
                .map(m -> new MetricInfo().name(m.name()).description(m.description())
                        .dimensions(List.copyOf(m.dimensions().keySet())).filters(List.copyOf(m.filters().keySet())))
                .toList();
        return new MetricList().metrics(metrics);
    }

    public MetricSeries queryMetric(Caller caller, MetricQuery request) {
        Catalogue catalogue = catalogue(caller);
        Metric metric = catalogue.metrics().get(request.getMetric());
        if (metric == null) {
            throw ReportingException.notFound("Unknown metric.");
        }
        if (!caller.groups().contains(metric.group())) {
            throw ReportingException.forbidden("This metric is not part of the caller's plan.");
        }
        checkRange(request.getFrom(), request.getTo());
        OffsetDateTime from = metric.fullHistory() ? request.getFrom() : clamp(request.getFrom(), caller);
        String interval = request.getInterval() == null ? "day" : request.getInterval().getValue();
        List<String> groupBy = request.getGroupBy() == null ? List.of() : request.getGroupBy();

        List<Map<String, Object>> rows = clickHouse.query(catalogue.database(), caller.scope(), QueryBuilder.metric(
                metric, caller.scope(), from, request.getTo(), interval, groupBy, request.getFilters()));

        List<MetricPoint> points = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            // A sum over nothing but NULLs is NULL: a day without an amount, not a failure.
            MetricPoint point = new MetricPoint()
                    .value(row.get("value") instanceof Number value ? value.doubleValue() : 0d);
            if (row.get("time") != null) {
                point.time(storedTime(row.get("time").toString()));
            }
            if (!groupBy.isEmpty()) {
                Map<String, String> dimensions = new LinkedHashMap<>();
                for (int i = 0; i < groupBy.size(); i++) {
                    dimensions.put(groupBy.get(i), String.valueOf(row.get("d" + i)));
                }
                point.dimensions(dimensions);
            }
            points.add(point);
        }
        return new MetricSeries().metric(metric.name()).from(from).to(request.getTo()).points(points);
    }

    public EventPage searchEvents(Caller caller, EventSearch request) {
        Catalogue catalogue = catalogue(caller);
        EventSource source = events(catalogue);
        checkRange(request.getFrom(), request.getTo());
        int limit = request.getLimit() == null ? 50 : request.getLimit();
        List<Map<String, Object>> rows = clickHouse.query(catalogue.database(), caller.scope(),
                QueryBuilder.events(source, caller.scope(), clamp(request.getFrom(), caller), request.getTo(),
                        request.getFilters(), limit, Cursor.decode(request.getCursor())));

        EventPage page = new EventPage();
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            Map<String, Object> last = rows.get(limit - 1);
            page.nextCursor(new Cursor(String.valueOf(last.get("time")), String.valueOf(last.get("eventId"))).encode());
        }
        return page.events(new ArrayList<>(rows));
    }

    public Map<String, Object> getEvent(Caller caller, UUID eventId) {
        return row(caller, eventId.toString());
    }

    public EventPage getTrace(Caller caller, String correlationId) {
        Catalogue catalogue = catalogue(caller);
        List<Map<String, Object>> events = new ArrayList<>();
        for (EventSource source : catalogue.trace()) {
            events.addAll(clickHouse.query(catalogue.database(), caller.scope(),
                    QueryBuilder.trace(source, caller.scope(), correlationId, MAX_TRACE_EVENTS, earliest(caller))));
        }
        events.sort(Comparator.comparing(row -> String.valueOf(row.get("time"))));
        return new EventPage().events(events);
    }

    public EventBody getBody(Caller caller, UUID eventId) {
        Catalogue catalogue = catalogue(caller);
        if (catalogue.bodies() == null || !bodies.enabled()) {
            throw ReportingException.notFound("This deployment keeps no bodies.");
        }
        if (!caller.groups().contains(catalogue.bodies().group())) {
            throw ReportingException.forbidden("Bodies are not part of the caller's plan.");
        }
        // The row comes through the scope's row policy: no row, no key, so another scope's object can't be named.
        Map<String, Object> row = row(caller, eventId.toString());
        Object received = row.get("received");
        if (received == null) {
            throw ReportingException.notFound("Unknown event.");
        }
        String key = BodyStore.key(catalogue.bodies().keyTemplate(), caller.tenant(), caller.scope(),
                eventId.toString(), storedTime(received.toString()));
        JsonNode event = bodies.read(key)
                .orElseThrow(() -> ReportingException.notFound("The bodies of this event are no longer kept."));
        JsonNode extra = event.path("extra");
        EventBody body = new EventBody();
        body.setRequestBody(text(extra.path("request").path("body")));
        body.setResponseBody(text(extra.path("response").path("body")));
        body.setRequestBodyTruncated(extra.path("request").path("bodyTruncated").asBoolean(false));
        body.setResponseBodyTruncated(extra.path("response").path("bodyTruncated").asBoolean(false));
        return body;
    }

    public Status getStatus(Caller caller) {
        Catalogue catalogue = catalogue(caller);
        Status status = new Status();
        if (catalogue.status() == null) {
            return status;
        }
        List<Map<String, Object>> rows = clickHouse.query(catalogue.database(), caller.scope(),
                QueryBuilder.dataAsOf(catalogue.status().table(), catalogue.status().scopeColumn(),
                        catalogue.status().received(), caller.scope()));
        if (!rows.isEmpty() && ((Number) rows.get(0).get("rows")).longValue() > 0) {
            status.dataAsOf(storedTime(String.valueOf(rows.get(0).get("dataAsOf"))));
        }
        return status;
    }

    private Map<String, Object> row(Caller caller, String eventId) {
        Catalogue catalogue = catalogue(caller);
        List<Map<String, Object>> rows = clickHouse.query(catalogue.database(), caller.scope(),
                QueryBuilder.event(events(catalogue), caller.scope(), eventId, earliest(caller)));
        if (rows.isEmpty()) {
            throw ReportingException.notFound("Unknown event.");
        }
        return rows.get(0);
    }

    private Catalogue catalogue(Caller caller) {
        return catalogues.forTenant(caller.tenant())
                .orElseThrow(() -> ReportingException.forbidden("This tenant has no reports."));
    }

    private static EventSource events(Catalogue catalogue) {
        if (catalogue.events() == null) {
            throw ReportingException.notFound("This tenant has no event list.");
        }
        return catalogue.events();
    }

    private OffsetDateTime clamp(OffsetDateTime from, Caller caller) {
        OffsetDateTime earliest = earliest(caller);
        return earliest != null && from.isBefore(earliest) ? earliest : from;
    }

    /** The start of what this caller may see, whichever way it asks. Null: no limit. */
    private OffsetDateTime earliest(Caller caller) {
        return caller.historyDays() == null ? null : OffsetDateTime.now(clock).minusDays(caller.historyDays());
    }

    private static void checkRange(OffsetDateTime from, OffsetDateTime to) {
        if (!from.isBefore(to)) {
            throw ReportingException.badRequest("'from' must be before 'to'.");
        }
        // Past DateTime64's range the server refuses the query, which must not read as an outage.
        if (from.getYear() < MIN_YEAR || to.getYear() > MAX_YEAR) {
            throw ReportingException.badRequest("The range must lie between the years " + MIN_YEAR + " and " + MAX_YEAR + ".");
        }
    }

    private static OffsetDateTime storedTime(String text) {
        return LocalDateTime.parse(text, STORED_TIME).atOffset(ZoneOffset.UTC);
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
