package io.labs64.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.reporting.body.BodyStore;
import io.labs64.reporting.caller.Caller;
import io.labs64.reporting.catalogue.Catalogue;
import io.labs64.reporting.catalogue.CatalogueRegistry;
import io.labs64.reporting.clickhouse.ClickHouseClient;
import io.labs64.reporting.model.EventBody;

class ReportingServiceBodiesTest {

    private static final UUID EVENT = UUID.fromString("0192f0aa-0000-7000-8000-000000000001");

    @Test
    void aBatchedBodyIsSearchedInTheFolderOfTheEventsReceiptMinute() throws Exception {
        Catalogue catalogue = CatalogueRegistry.parse(Map.of("database", "demo", "metrics", Map.of(),
                "events", Map.of("table", "calls", "scopeColumn", "customer", "time", "event_time", "id", "event_id",
                        "correlation", "correlation_id",
                        "columns", Map.of("eventId", "toString(event_id)", "time", "toString(event_time)", "received",
                                "toString(received)")),
                "bodies", Map.of("group", "paid", "prefixTemplate",
                        "bodies/tenant={tenant}/v={scope}/day={dd}/hour={HH}/minute={mm}/")));
        ClickHouseClient clickHouse = mock(ClickHouseClient.class);
        when(clickHouse.query(any(), eq("A"), any()))
                .thenReturn(List.of(Map.of("received", "2026-10-01 22:30:05.678")));
        BodyStore bodies = mock(BodyStore.class);
        when(bodies.enabled()).thenReturn(true);
        when(bodies.find("bodies/tenant=demo/v=A/day=01/hour=22/minute=30/", EVENT.toString()))
                .thenReturn(Optional.of(new ObjectMapper().readTree("{\"extra\":{\"request\":{\"body\":\"x\"}}}")));
        ReportingService service = new ReportingService(new CatalogueRegistry(Map.of("demo", catalogue)), clickHouse, bodies);

        EventBody body = service.getBody(new Caller("demo", "A", Set.of("paid"), null), EVENT);

        assertThat(body.getRequestBody()).isEqualTo("x");
    }

    // --- the original by the archive key its row holds ---

    private static final String KEY = "tenants/tenant=demo/retention=30/v=A/year=2026/batch-1.jsonl.gz";
    private static final String STORED = """
            {"eventId":"%s","tenantId":"demo","extra":{"plan":"p1","request":{"body":"x","header":{"a":["1"]}}}}
            """.formatted(EVENT).trim();

    private static Catalogue byKey(Map<String, Object> bodies) {
        return CatalogueRegistry.parse(Map.of("database", "demo", "metrics", Map.of(),
                "events", Map.of("table", "calls", "scopeColumn", "customer", "time", "event_time", "id", "event_id",
                        "correlation", "correlation_id",
                        "columns", Map.of("eventId", "toString(event_id)", "time", "toString(event_time)", "received",
                                "toString(received)")),
                "bodies", bodies));
    }

    private static final Map<String, Object> BY_KEY = Map.of("keyColumn", "archive_key",
            "keyPattern", "tenants/tenant={tenant}/*/v={scope}/*", "exclude", List.of("tenantId", "extra.plan"));

    private static ClickHouseClient rowWithKey(String key) {
        ClickHouseClient clickHouse = mock(ClickHouseClient.class);
        java.util.HashMap<String, Object> row = new java.util.HashMap<>();
        row.put("archiveKey", key);
        when(clickHouse.query(any(), eq("A"), any())).thenReturn(List.of(row));
        return clickHouse;
    }

    private static BodyStore archive() throws Exception {
        BodyStore bodies = mock(BodyStore.class);
        when(bodies.enabled()).thenReturn(true);
        org.mockito.Mockito.lenient().when(bodies.findIn(KEY, EVENT.toString()))
                .thenReturn(Optional.of(new ObjectMapper().readTree(STORED)));
        return bodies;
    }

    private static ReportingService service(Catalogue catalogue, ClickHouseClient clickHouse, BodyStore bodies) {
        return new ReportingService(new CatalogueRegistry(Map.of("demo", catalogue)), clickHouse, bodies);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOriginalIsReadFromTheKeyInItsRowWithoutTheExcludedFieldsAndForEveryGroup() throws Exception {
        BodyStore bodies = archive();

        EventBody body = service(byKey(BY_KEY), rowWithKey(KEY), bodies)
                .getBody(new Caller("demo", "A", Set.of("base"), null), EVENT);

        Map<String, Object> event = (Map<String, Object>) body.getEvent();
        assertThat(event).doesNotContainKey("tenantId").containsEntry("eventId", EVENT.toString());
        Map<String, Object> extra = (Map<String, Object>) event.get("extra");
        assertThat(extra).doesNotContainKey("plan");
        assertThat(((Map<String, Object>) extra.get("request")).get("body")).isEqualTo("x");
        assertThat(body.getRequestBody()).as("the deprecated field is still filled").isEqualTo("x");
        assertThat(body.getResponseBody()).isNull();
        org.mockito.Mockito.verify(bodies, org.mockito.Mockito.never()).find(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExcludedPathThroughAListIsRemovedFromEveryEntryAndFromTheDeprecatedFields() throws Exception {
        BodyStore bodies = mock(BodyStore.class);
        when(bodies.enabled()).thenReturn(true);
        when(bodies.findIn(KEY, EVENT.toString())).thenReturn(Optional.of(new ObjectMapper().readTree("""
                {"eventId":"e","extra":{"lines":[{"price":1,"secret":"a"},{"price":2,"secret":"b"}],
                 "request":{"body":"x"}}}""")));
        Map<String, Object> excluding = new java.util.HashMap<>(BY_KEY);
        excluding.put("exclude", List.of("extra.lines.secret", "extra.request.body", "extra.missing.x"));

        EventBody body = service(byKey(excluding), rowWithKey(KEY), bodies)
                .getBody(new Caller("demo", "A", Set.of("base"), null), EVENT);

        Map<String, Object> extra = (Map<String, Object>) ((Map<String, Object>) body.getEvent()).get("extra");
        assertThat((List<Map<String, Object>>) extra.get("lines")).containsExactly(Map.of("price", 1), Map.of("price", 2));
        assertThat(body.getRequestBody()).isNull();
    }

    @Test
    void aScopeGetsOnlyItsShareOfArchiveReads() throws Exception {
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        BodyStore slow = mock(BodyStore.class);
        when(slow.enabled()).thenReturn(true);
        when(slow.findIn(any(), any())).thenAnswer(call -> {
            reading.countDown();
            release.await();
            return Optional.of(new ObjectMapper().readTree(STORED));
        });
        ReportingService service = new ReportingService(new CatalogueRegistry(Map.of("demo", byKey(BY_KEY))),
                rowWithKey(KEY), slow, java.time.Clock.systemUTC(),
                new io.labs64.reporting.clickhouse.ScopeLimiter(1, java.time.Duration.ofMillis(10)));
        Caller caller = new Caller("demo", "A", Set.of("base"), null);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<EventBody> first = pool.submit(() -> service.getBody(caller, EVENT));
            reading.await();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getBody(caller, EVENT))
                    .isInstanceOfSatisfying(ReportingException.class,
                            e -> assertThat(e.status().value()).isEqualTo(429));

            release.countDown();
            assertThat(first.get().getEvent()).isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aKeyOutsideTheCallersTenantOrScopeIsNeverRead() throws Exception {
        BodyStore bodies = archive();
        String otherScope = "tenants/tenant=demo/retention=30/v=B/year=2026/batch-1.jsonl.gz";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(byKey(BY_KEY), rowWithKey(otherScope), bodies)
                .getBody(new Caller("demo", "A", Set.of("base"), null), EVENT))
                .isInstanceOf(ReportingException.class).hasMessageContaining("no longer kept");
        org.mockito.Mockito.verify(bodies, org.mockito.Mockito.never()).findIn(any(), any());
    }

    @Test
    void aRowWithoutAKeyOrAnExpiredObjectIsNoLongerKept() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(byKey(BY_KEY), rowWithKey(""), archive())
                .getBody(new Caller("demo", "A", Set.of("base"), null), EVENT))
                .isInstanceOf(ReportingException.class).hasMessageContaining("no longer kept");

        BodyStore expired = mock(BodyStore.class);
        when(expired.enabled()).thenReturn(true);
        when(expired.findIn(KEY, EVENT.toString())).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(byKey(BY_KEY), rowWithKey(KEY), expired)
                .getBody(new Caller("demo", "A", Set.of("base"), null), EVENT))
                .isInstanceOf(ReportingException.class).hasMessageContaining("no longer kept");
    }

    @Test
    void aGroupStillGatesTheOriginalWhenTheCatalogueNamesOne() throws Exception {
        Map<String, Object> withGroup = new java.util.HashMap<>(BY_KEY);
        withGroup.put("group", "paid");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(byKey(withGroup), rowWithKey(KEY), archive())
                .getBody(new Caller("demo", "A", Set.of("base"), null), EVENT))
                .isInstanceOf(ReportingException.class).hasMessageContaining("plan");
    }
}
