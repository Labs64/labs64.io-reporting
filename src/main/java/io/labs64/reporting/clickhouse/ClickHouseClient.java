package io.labs64.reporting.clickhouse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.reporting.ReportingException;
import io.labs64.reporting.ReportingProperties;
import io.labs64.reporting.query.SqlQuery;

/**
 * ClickHouse's HTTP interface, which is all this service needs: bound parameters, a per-query setting and JSON rows.
 * {@code SQL_scope} drives the row policy on the server, so a query that forgot its filter still sees one scope only.
 */
@Component
public class ClickHouseClient {

    private static final Logger LOG = LoggerFactory.getLogger(ClickHouseClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** TOO_MANY_ROWS and TOO_MANY_ROWS_OR_BYTES: the question is too big, the store is fine. */
    private static final Set<String> RESULT_TOO_LARGE = Set.of("158", "396");
    private static final String VALUE_OUT_OF_RANGE = "457";

    private final ReportingProperties.ClickHouse properties;
    private final HttpClient http;

    @Autowired
    public ClickHouseClient(ReportingProperties properties) {
        this(properties.clickhouse());
    }

    public ClickHouseClient(ReportingProperties.ClickHouse properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout()).build();
    }

    public List<Map<String, Object>> query(String database, String scope, SqlQuery query) {
        Map<String, String> url = new LinkedHashMap<>();
        url.put("database", database);
        url.put("SQL_scope", scope);
        url.put("output_format_json_quote_64bit_integers", "0");
        // The status is the query's outcome: without this, a failure after the first bytes still answers 200.
        url.put("wait_end_of_query", "1");
        query.parameters().forEach((name, value) -> url.put("param_" + name, value));

        List<String> pairs = new ArrayList<>();
        url.forEach((name, value) -> pairs.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));

        HttpRequest request = HttpRequest
                .newBuilder(URI.create(properties.url().replaceAll("/+$", "") + "/?" + String.join("&", pairs)))
                .timeout(properties.requestTimeout()).header("X-ClickHouse-User", properties.username())
                .header("X-ClickHouse-Key", properties.password())
                .POST(HttpRequest.BodyPublishers.ofString(query.sql() + " FORMAT JSON")).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.headers().firstValue("X-ClickHouse-Exception-Code").filter(RESULT_TOO_LARGE::contains)
                    .isPresent()) {
                // One caller's question must not read as an outage: a backend's circuit breaker would stop everyone's.
                throw ReportingException.badRequest("The answer is too large: narrow the range or use a longer interval.");
            }
            if (response.headers().firstValue("X-ClickHouse-Exception-Code").filter(VALUE_OUT_OF_RANGE::equals)
                    .isPresent()) {
                throw ReportingException.badRequest("A value of the request is out of range.");
            }
            if (response.statusCode() != 200) {
                // The server's text may quote a value of the request: the log only, never the caller.
                LOG.error("ClickHouse refused a query. status={}, answer={}", response.statusCode(),
                        response.body().lines().findFirst().orElse(""));
                throw ReportingException.unavailable("The report store could not answer.", null);
            }
            return JSON.convertValue(JSON.readTree(response.body()).path("data"), new TypeReference<>() {
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ReportingException.unavailable("The report store could not answer.", e);
        } catch (IOException e) {
            throw ReportingException.unavailable("The report store could not answer.", e);
        }
    }
}
