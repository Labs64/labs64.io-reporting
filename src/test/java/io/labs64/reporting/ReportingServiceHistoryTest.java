package io.labs64.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.labs64.reporting.body.BodyStore;
import io.labs64.reporting.caller.Caller;
import io.labs64.reporting.catalogue.CatalogueRegistry;
import io.labs64.reporting.clickhouse.ClickHouseClient;
import io.labs64.reporting.model.MetricQuery;

class ReportingServiceHistoryTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final OffsetDateTime LONG_AGO = OffsetDateTime.parse("2000-01-01T00:00:00Z");

    private OffsetDateTime effectiveStart(Integer ceiling, Integer asserted, boolean fullHistory) {
        Map<String, Object> metric = new HashMap<>(Map.of("group", "g", "table", "t", "scopeColumn", "c", "time", "at", "value", "count()"));
        metric.put("fullHistory", fullHistory);
        Map<String, Object> root = new HashMap<>(Map.of("database", "d", "metrics", Map.of("m", metric)));
        root.put("maxHistoryDays", ceiling);
        ClickHouseClient clickHouse = mock(ClickHouseClient.class);
        when(clickHouse.query(any(), any(), any())).thenReturn(List.of());
        ReportingService service = new ReportingService(new CatalogueRegistry(Map.of("demo", CatalogueRegistry.parse(root))),
                clickHouse, mock(BodyStore.class), Clock.fixed(NOW, ZoneOffset.UTC));

        return service.queryMetric(new Caller("demo", "A", Set.of("g"), asserted),
                new MetricQuery().metric("m").from(LONG_AGO).to(NOW.atOffset(ZoneOffset.UTC))).getFrom();
    }

    private static OffsetDateTime daysAgo(int days) {
        return NOW.atOffset(ZoneOffset.UTC).minusDays(days);
    }

    @Test
    void theShorterOfWhatTheBackendAssertsAndWhatTheCatalogueAllowsWins() {
        assertThat(effectiveStart(90, 30, false)).isEqualTo(daysAgo(30));
        assertThat(effectiveStart(30, 90, false)).isEqualTo(daysAgo(30));
    }

    @Test
    void aBackendThatAssertsNothingStillGetsTheCeiling() {
        assertThat(effectiveStart(90, null, false)).isEqualTo(daysAgo(90));
    }

    @Test
    void withoutEitherNothingIsLimited() {
        assertThat(effectiveStart(null, null, false)).isEqualTo(LONG_AGO);
    }

    @Test
    void aFullHistoryMetricStaysExempt() {
        assertThat(effectiveStart(90, 30, true)).isEqualTo(LONG_AGO);
    }

    /** minusDays would give a year the store refuses, and a read of one event would answer 400 for no fault of its own. */
    @Test
    void anAbsurdLimitIsNoLimitRatherThanADateTheStoreRefuses() {
        assertThat(effectiveStart(null, Integer.MAX_VALUE, false)).isEqualTo(LONG_AGO);
    }
}
