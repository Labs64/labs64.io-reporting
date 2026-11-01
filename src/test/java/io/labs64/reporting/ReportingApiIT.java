package io.labs64.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.reporting.clickhouse.ClickHouseClient;
import io.labs64.reporting.query.SqlQuery;

/**
 * The whole service against a real ClickHouse: gateway headers in, JSON out, and the row policy underneath. Skipped
 * where no Docker runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportingApiIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static GenericContainer<?> clickHouse;

    @LocalServerPort
    private int port;

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            clickHouse = new GenericContainer<>("clickhouse/clickhouse-server:26.10").withExposedPorts(8123)
                    .withEnv("CLICKHOUSE_USER", "admin").withEnv("CLICKHOUSE_PASSWORD", "admin")
                    .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                    .waitingFor(Wait.forHttp("/ping").forPort(8123));
            clickHouse.start();
            try {
                String schema = new ClassPathResource("clickhouse-schema.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
                for (String statement : schema.split(";\\s*\\n")) {
                    if (!statement.isBlank()) {
                        admin(statement);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("labs64.reporting.catalogue-directory", () -> "src/test/resources/catalogue");
        if (clickHouse != null) {
            registry.add("labs64.reporting.clickhouse.url", ReportingApiIT::clickHouseUrl);
            registry.add("labs64.reporting.clickhouse.username", () -> "reporting");
            registry.add("labs64.reporting.clickhouse.password", () -> "reader");
        }
    }

    @BeforeAll
    static void needsDocker() {
        assumeTrue(clickHouse != null, "Docker is not available");
    }

    private static String clickHouseUrl() {
        return "http://" + clickHouse.getHost() + ":" + clickHouse.getMappedPort(8123);
    }

    private static void admin(String sql) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(clickHouseUrl() + "/")).header("X-ClickHouse-User", "admin")
                        .header("X-ClickHouse-Key", "admin").POST(HttpRequest.BodyPublishers.ofString(sql)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private HttpResponse<String> post(String path, String scope, String groups, String historyDays, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Auth-User", "service:backend")
                .header("X-Auth-Tenant", "demo").header("X-Auth-Scopes", "reporting:read-delegated")
                .header("X-Request-ID", "test").POST(HttpRequest.BodyPublishers.ofString(body));
        if (scope != null) {
            request.header("X-Reporting-Scope", scope);
        }
        if (groups != null) {
            request.header("X-Reporting-Groups", groups);
        }
        if (historyDays != null) {
            request.header("X-Reporting-History-Days", historyDays);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static final String RANGE = "\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-11-01T00:00:00Z\"";

    @Test
    void theRowPolicyHoldsEvenForAQueryWithoutAFilter() {
        ClickHouseClient client = new ClickHouseClient(new ReportingProperties.ClickHouse(clickHouseUrl(), "reporting",
                "reader", java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(6)));

        List<Map<String, Object>> a = client.query("demo", "A", new SqlQuery("SELECT count() AS c FROM calls", Map.of()));
        List<Map<String, Object>> b = client.query("demo", "B", new SqlQuery("SELECT count() AS c FROM calls", Map.of()));

        assertThat(a.get(0).get("c")).isEqualTo(3);
        assertThat(b.get(0).get("c")).isEqualTo(1);
    }

    @Test
    void aMetricCountsOnlyTheCallersScopePerDay() throws Exception {
        JsonNode series = ok(post("/metrics/query", "A", "base", null, "{\"metric\":\"calls.allTime\"," + RANGE + "}"));

        assertThat(series.path("points").size()).isEqualTo(2);
        assertThat(series.path("points").get(0).path("value").asDouble()).isEqualTo(2);
        assertThat(series.path("points").get(0).path("time").asText()).startsWith("2026-10-01T00:00");
        assertThat(series.path("points").get(1).path("value").asDouble()).isEqualTo(1);
    }

    @Test
    void dimensionsAndFiltersComeFromTheCatalogue() throws Exception {
        JsonNode series = ok(post("/metrics/query", "A", "base", null, "{\"metric\":\"calls.allTime\",\"interval\":\"none\"," + RANGE + "}"));
        assertThat(series.path("points").get(0).path("value").asDouble()).isEqualTo(3);

        JsonNode byAction = ok(post("/metrics/query", "A", "base", "36500",
                "{\"metric\":\"calls\",\"interval\":\"none\",\"groupBy\":[\"action\"],\"filters\":{\"action\":[\"write\",\"x' OR 1=1 --\"]},"
                        + RANGE + "}"));
        assertThat(byAction.path("points").size()).isEqualTo(1);
        assertThat(byAction.path("points").get(0).path("dimensions").path("action").asText()).isEqualTo("write");

        assertThat(post("/metrics/query", "A", "base", null, "{\"metric\":\"calls\",\"groupBy\":[\"status\"]," + RANGE + "}")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void aMetricOutsideTheGroupsIsForbiddenAndNotListed() throws Exception {
        assertThat(post("/metrics/query", "A", "base", null, "{\"metric\":\"errors\"," + RANGE + "}").statusCode())
                .isEqualTo(403);
        JsonNode list = ok(post("/metrics/list", "A", "base", null, ""));
        assertThat(list.path("metrics").findValuesAsText("name")).containsExactly("calls", "calls.allTime");
        assertThat(ok(post("/metrics/query", "A", "base,paid", null, "{\"metric\":\"errors\",\"interval\":\"none\"," + RANGE + "}"))
                .path("points").get(0).path("value").asDouble()).isEqualTo(1);
    }

    @Test
    void theHistoryLimitMovesTheStartUnlessTheMetricKeepsFullHistory() throws Exception {
        JsonNode limited = ok(post("/metrics/query", "A", "base", "1", "{\"metric\":\"calls\"," + RANGE + "}"));
        assertThat(limited.path("points").size()).isZero();

        JsonNode full = ok(post("/metrics/query", "A", "base", "1", "{\"metric\":\"calls.allTime\"," + RANGE + "}"));
        assertThat(full.path("points").size()).isEqualTo(2);
    }

    @Test
    void eventsArePagedNewestFirstWithoutRepeats() throws Exception {
        JsonNode first = ok(post("/events/search", "A", "base", null, "{\"limit\":2," + RANGE + "}"));
        assertThat(first.path("events").findValuesAsText("eventId")).containsExactly(
                "00000000-0000-0000-0000-00000000000c", "00000000-0000-0000-0000-00000000000b");

        JsonNode second = ok(post("/events/search", "A", "base", null,
                "{\"limit\":2,\"cursor\":\"" + first.path("nextCursor").asText() + "\"," + RANGE + "}"));
        assertThat(second.path("events").findValuesAsText("eventId"))
                .containsExactly("00000000-0000-0000-0000-00000000000a");
        assertThat(second.path("nextCursor").isMissingNode() || second.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void anotherScopesEventAndTraceAreNotThere() throws Exception {
        String eventOfB = "{\"eventId\":\"00000000-0000-0000-0000-00000000000d\"}";
        assertThat(post("/events/get", "A", "base", null, eventOfB).statusCode()).isEqualTo(404);
        assertThat(ok(post("/events/get", "B", "base", null, eventOfB)).path("action").asText()).isEqualTo("read");

        assertThat(ok(post("/traces/get", "A", "base", null, "{\"correlationId\":\"R4\"}")).path("events").size()).isZero();
        assertThat(ok(post("/traces/get", "B", "base", null, "{\"correlationId\":\"R4\"}")).path("events").size()).isEqualTo(1);
    }

    @Test
    void aSumOverNothingButNullsIsZeroNotAFailure() throws Exception {
        JsonNode series = ok(post("/metrics/query", "A", "test", null,
                "{\"metric\":\"calls.amount\",\"interval\":\"none\"," + RANGE + "}"));
        assertThat(series.path("points").get(0).path("value").asDouble()).isZero();
    }

    @Test
    void aTooLargeAnswerIsTheCallersErrorNotAnOutage() {
        ClickHouseClient client = new ClickHouseClient(new ReportingProperties.ClickHouse(clickHouseUrl(), "reporting",
                "reader", java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(6)));

        assertThatThrownBy(() -> client.query("demo", "A", new SqlQuery(
                "SELECT event_id FROM calls SETTINGS max_result_rows = 1, result_overflow_mode = 'throw'", Map.of())))
                .isInstanceOfSatisfying(ReportingException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** Five 503s open the breaker of the service's backend for every tenant, so a bad date must stay a 400. */
    @Test
    void aDateOutsideTheStoresRangeIsTheCallersErrorNotAnOutage() throws Exception {
        for (String range : List.of("\"from\":\"+12345-01-01T00:00:00Z\",\"to\":\"+12346-01-01T00:00:00Z\"",
                "\"from\":\"1000-01-01T00:00:00Z\",\"to\":\"2026-01-01T00:00:00Z\"",
                "\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"+12346-01-01T00:00:00Z\"")) {
            assertThat(post("/metrics/query", "A", "base", null,
                    "{\"metric\":\"calls\",\"interval\":\"day\"," + range + "}").statusCode()).as(range).isEqualTo(400);
            assertThat(post("/events/search", "A", "base", null, "{" + range + "}").statusCode()).as(range).isEqualTo(400);
        }
    }

    @Test
    void aValueTheServerCannotConvertIsTheCallersErrorNotAnOutage() {
        ClickHouseClient client = new ClickHouseClient(new ReportingProperties.ClickHouse(clickHouseUrl(), "reporting",
                "reader", java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(6)));

        assertThatThrownBy(() -> client.query("demo", "A", new SqlQuery("SELECT {at:DateTime64(3, 'UTC')}",
                Map.of("at", "+12345-01-01 00:00:00.000"))))
                .isInstanceOfSatisfying(ReportingException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** Library pairings can fail only on a request (Jackson has before), so these are called, not just wired. */
    @Test
    void metricsAndTheApiDocumentAnswer() throws Exception {
        ok(post("/status", "A", "base", null, ""));
        HttpResponse<String> metrics = get("/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("reporting_operation_seconds_count");

        HttpResponse<String> health = get("/actuator/health");
        assertThat(health.statusCode()).as(health.body()).isEqualTo(200);

        HttpResponse<String> document = get("/v3/api-docs");
        assertThat(document.statusCode()).as(document.body()).isEqualTo(200);
        assertThat(document.body()).contains("/metrics/query");
    }

    @Test
    void statusSaysHowFreshTheDataIs() throws Exception {
        assertThat(ok(post("/status", "A", "base", null, "")).path("dataAsOf").asText()).startsWith("2026-10-02T10:00:01");
        assertThat(ok(post("/status", "nobody", "base", null, "")).path("dataAsOf").isTextual()).isFalse();
    }

    @Test
    void theCallerIsCheckedBeforeAnythingElse() throws Exception {
        assertThat(post("/metrics/list", null, "base", null, "").statusCode()).isEqualTo(400);
        assertThat(post("/metrics/list", "bad scope!", "base", null, "").statusCode()).isEqualTo(400);

        HttpResponse<String> noGateway = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics/list"))
                        .header("X-Reporting-Scope", "A").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noGateway.statusCode()).isEqualTo(401);

        HttpResponse<String> wrongScope = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics/list"))
                        .header("X-Auth-User", "service:other").header("X-Auth-Tenant", "demo")
                        .header("X-Auth-Scopes", "audit-event:write").header("X-Request-ID", "t")
                        .header("X-Reporting-Scope", "A").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(wrongScope.statusCode()).isEqualTo(403);
    }
}
