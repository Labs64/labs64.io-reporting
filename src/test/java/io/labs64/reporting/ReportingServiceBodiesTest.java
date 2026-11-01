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
}
