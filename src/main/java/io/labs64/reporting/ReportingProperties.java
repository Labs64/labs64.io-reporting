package io.labs64.reporting;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("labs64.reporting")
public record ReportingProperties(
        @DefaultValue("/etc/labs64/reporting/catalogue") Path catalogueDirectory,
        ClickHouse clickhouse,
        Bodies bodies) {

    public record ClickHouse(
            @DefaultValue("http://localhost:8123") String url,
            @DefaultValue("reporting") String username,
            @DefaultValue("") String password,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("6s") Duration requestTimeout,
            @DefaultValue("4") int maxQueriesPerScope,
            @DefaultValue("500ms") Duration scopeWait) {
    }

    /** {@code bucket} empty: no bodies in this deployment. */
    public record Bodies(@DefaultValue("") String bucket, @DefaultValue("") String region) {
    }
}
