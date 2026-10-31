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

    private final ReportingProperties.ClickHouse properties;
    private final HttpClient http;

    @Autowired
    public ClickHouseClient(ReportingProperties properties) {
        this(properties.clickhouse());
    }

    public ClickHouseClient(ReportingProperties.ClickHouse properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    public List<Map<String, Object>> query(String database, String scope, SqlQuery query) {
        Map<String, String> url = new LinkedHashMap<>();
        url.put("database", database);
        url.put("SQL_scope", scope);
        url.put("output_format_json_quote_64bit_integers", "0");
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
