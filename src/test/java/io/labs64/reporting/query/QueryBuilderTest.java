package io.labs64.reporting.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.labs64.reporting.ReportingException;
import io.labs64.reporting.catalogue.Catalogue.Metric;

class QueryBuilderTest {

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-01T00:00:00+02:00");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-10-02T00:00:00Z");

    private static Metric metric() {
        return new Metric("m", null, "base", "t", "customer", "day", "sum(n)", false, true, "origin != 'x'",
                Map.of("action", "action_name"), Map.of("action", "action_name"));
    }

    @Test
    void everyRequestValueIsABoundParameter() {
        SqlQuery query = QueryBuilder.metric(metric(), "V'1", FROM, TO, "week", List.of("action"),
                Map.of("action", List.of("a' OR 1=1 --")));

        assertThat(query.sql()).doesNotContain("V'1").doesNotContain("OR 1=1").contains("customer = {scope:String}")
                .contains("FROM t FINAL").contains("AND (origin != 'x')").contains("toMonday(")
                .contains("GROUP BY time, d0");
        assertThat(query.parameters()).containsEntry("scope", "V'1").containsEntry("from", "2026-09-30 22:00:00.000")
                .containsEntry("filter0", "['a\\' OR 1=1 --']");
    }

    @Test
    void aDimensionOrFilterMustBeInTheCatalogue() {
        assertThatThrownBy(() -> QueryBuilder.metric(metric(), "V", FROM, TO, "day", List.of("1; DROP"), null))
                .isInstanceOf(ReportingException.class).hasMessageContaining("Unknown dimension");
        assertThatThrownBy(() -> QueryBuilder.metric(metric(), "V", FROM, TO, "day", List.of(), Map.of("x", List.of("y"))))
                .hasMessageContaining("Unknown filter");
        assertThatThrownBy(() -> QueryBuilder.metric(metric(), "V", FROM, TO, "decade", List.of(), null))
                .hasMessageContaining("Unknown interval");
    }

    @Test
    void anArrayLiteralEscapesItsValues() {
        assertThat(QueryBuilder.arrayLiteral(List.of("a", "b'c", "d\\e"))).isEqualTo("['a','b\\'c','d\\\\e']");
    }

    @Test
    void aCursorSurvivesTheRoundTripAndRefusesAnythingElse() {
        Cursor cursor = new Cursor("2026-10-01 10:00:00.000", "00000000-0000-0000-0000-00000000000a");
        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(Cursor.decode(null)).isNull();
        assertThatThrownBy(() -> Cursor.decode("not a cursor!")).isInstanceOf(ReportingException.class);
        String tampered = new Cursor("x') OR 1=1", "a").encode();
        assertThatThrownBy(() -> Cursor.decode(tampered)).isInstanceOf(ReportingException.class);
    }

    @Test
    void oneEventAndATraceStayInsideTheCallersHistory() {
        var source = new io.labs64.reporting.catalogue.Catalogue.EventSource("events", "calls", "customer", "event_time",
                "event_id", "correlation_id", false, null, java.util.Map.of("eventId", "toString(event_id)"),
                java.util.Map.of());
        var earliest = java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z");

        var event = QueryBuilder.event(source, "A", "id", earliest);
        var trace = QueryBuilder.trace(source, "A", "R1", 10, earliest);

        org.assertj.core.api.Assertions.assertThat(event.sql()).contains("event_time >= {earliest:DateTime64(3)}");
        org.assertj.core.api.Assertions.assertThat(trace.sql()).contains("event_time >= {earliest:DateTime64(3)}");
        org.assertj.core.api.Assertions.assertThat(event.parameters()).containsEntry("earliest", "2026-01-01 00:00:00.000");
        org.assertj.core.api.Assertions.assertThat(QueryBuilder.event(source, "A", "id", null).sql())
                .doesNotContain("earliest");
    }
}
