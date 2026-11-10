package io.labs64.reporting.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import io.labs64.reporting.ReportingException;
import io.labs64.reporting.ReportingProperties;
import io.labs64.reporting.catalogue.Catalogue;
import io.labs64.reporting.catalogue.CatalogueRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RowPolicyCheckTest {

    private static final Map<String, Object> EVENT_COLUMNS = Map.of("eventId", "e");

    private final ClickHouseClient clickHouse = mock(ClickHouseClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private static Catalogue catalogue() {
        return CatalogueRegistry.parse(Map.of("database", "demo",
                "metrics", Map.of("m", Map.of("group", "g", "table", "metric_table", "scopeColumn", "c", "time", "t", "value", "count()")),
                "events", source("event_table"),
                "trace", List.of(Map.of("name", "n", "table", "trace_table", "scopeColumn", "c", "time", "t", "id", "i",
                        "correlation", "r", "columns", EVENT_COLUMNS)),
                "status", Map.of("table", "status_table", "scopeColumn", "c", "received", "r")));
    }

    private static Map<String, Object> source(String table) {
        return Map.of("table", table, "scopeColumn", "c", "time", "t", "id", "i", "correlation", "r", "columns", EVENT_COLUMNS);
    }

    private RowPolicyCheck check(ReportingProperties.RowPolicyCheck mode) {
        ReportingProperties properties = new ReportingProperties(Path.of("x"), null, null, mode);
        return new RowPolicyCheck(new CatalogueRegistry(Map.of("demo", catalogue())), clickHouse, properties, meters);
    }

    private void aRowComesBackFrom(String table) {
        when(clickHouse.query(eq("demo"), eq(RowPolicyCheck.PROBE_SCOPE), argThat(q -> q != null && q.sql().contains(" " + table + " "))))
                .thenReturn(List.of(Map.of("probe", 1)));
    }

    @Test
    void everyTableOfTheCatalogueIsAskedForAScopeNobodyHas() {
        when(clickHouse.query(any(), any(), any())).thenReturn(List.of());

        RowPolicyCheck check = check(ReportingProperties.RowPolicyCheck.STRICT);
        check.check();

        for (String table : List.of("metric_table", "event_table", "trace_table", "status_table")) {
            verify(clickHouse).query(eq("demo"), eq(RowPolicyCheck.PROBE_SCOPE),
                    argThat(q -> q.sql().equals("SELECT 1 AS probe FROM " + table + " LIMIT 1")));
        }
        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aRowOfThatScopeFailsReadinessAndNamesTheTable() {
        when(clickHouse.query(any(), any(), any())).thenReturn(List.of());
        aRowComesBackFrom("trace_table");

        RowPolicyCheck check = check(ReportingProperties.RowPolicyCheck.STRICT);
        check.check();

        assertThat(check.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(check.health().getDetails().get("unprotectedTables").toString()).contains("demo:demo.trace_table");
        assertThat(meters.get("reporting.row_policy.unprotected_tables").gauge().value()).isEqualTo(1);
    }

    @Test
    void warnModeReportsItButStaysReady() {
        when(clickHouse.query(any(), any(), any())).thenReturn(List.of());
        aRowComesBackFrom("metric_table");

        RowPolicyCheck check = check(ReportingProperties.RowPolicyCheck.WARN);
        check.check();

        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
        assertThat(check.health().getDetails()).containsKey("unprotectedTables");
        assertThat(meters.get("reporting.row_policy.unprotected_tables").gauge().value()).isEqualTo(1);
    }

    /** A store outage shows per query; taking every pod out on top would only turn it into a different failure. */
    @Test
    void aStoreThatDoesNotAnswerSaysNothingAboutAPolicy() {
        when(clickHouse.query(any(), any(), any())).thenThrow(ReportingException.unavailable("down", null));

        RowPolicyCheck check = check(ReportingProperties.RowPolicyCheck.STRICT);
        check.check();

        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aStoreThatDoesNotAnswerDoesNotClearAFinding() {
        when(clickHouse.query(any(), any(), any())).thenReturn(List.of());
        aRowComesBackFrom("trace_table");
        RowPolicyCheck check = check(ReportingProperties.RowPolicyCheck.STRICT);
        check.check();

        org.mockito.Mockito.reset(clickHouse);
        when(clickHouse.query(any(), any(), any())).thenThrow(ReportingException.unavailable("down", null));
        check.check();

        assertThat(check.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(check.health().getDetails().get("unprotectedTables").toString()).contains("demo:demo.trace_table");
    }

    @Test
    void aFixedPolicyClearsTheFinding() {
        when(clickHouse.query(any(), any(), any())).thenReturn(List.of(Map.of("probe", 1)));
        RowPolicyCheck check = check(ReportingProperties.RowPolicyCheck.STRICT);
        check.check();
        assertThat(check.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);

        when(clickHouse.query(any(), any(), any())).thenReturn(List.of());
        check.check();

        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void nothingIsAskedBeforeTheCheckRuns() {
        check(ReportingProperties.RowPolicyCheck.STRICT);
        verify(clickHouse, never()).query(any(), any(), any());
    }
}
