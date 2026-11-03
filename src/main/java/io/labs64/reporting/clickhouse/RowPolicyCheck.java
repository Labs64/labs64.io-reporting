package io.labs64.reporting.clickhouse;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.labs64.reporting.ReportingException;
import io.labs64.reporting.ReportingProperties;
import io.labs64.reporting.catalogue.Catalogue;
import io.labs64.reporting.catalogue.CatalogueRegistry;
import io.labs64.reporting.query.SqlQuery;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Proves the second line of defence by trying it: as the reader, each catalogue table is asked for a row of a scope
 * nobody has. A row means nothing filtered it. This asks the server what it really does, so a view protected by the
 * policy on its base table passes, and the reader needs no grant on the policy metadata.
 *
 * <p>Only that answer counts against readiness. A store that does not answer says nothing about a policy and already
 * shows as a 503 per query; it must not take the pods out as well. An empty table can't be proven, hence the recheck.
 */
@Component("rowPoliciesHealthIndicator")
public class RowPolicyCheck implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(RowPolicyCheck.class);

    /** Random per pod, within the scope format: it can't be a customer's. */
    static final String PROBE_SCOPE = "row-policy-probe-" + UUID.randomUUID();

    private final CatalogueRegistry catalogues;
    private final ClickHouseClient clickHouse;
    private final boolean strict;
    private volatile Set<String> unprotected = Set.of();

    public RowPolicyCheck(CatalogueRegistry catalogues, ClickHouseClient clickHouse, ReportingProperties properties,
            MeterRegistry meters) {
        this.catalogues = catalogues;
        this.clickHouse = clickHouse;
        this.strict = properties.rowPolicyCheck() == ReportingProperties.RowPolicyCheck.STRICT;
        Gauge.builder("reporting.row_policy.unprotected_tables", this, check -> check.unprotected.size()).register(meters);
    }

    /** Before the pod turns ready, so that a rollout stops here. */
    @EventListener(ApplicationReadyEvent.class)
    public void checkAtStart() {
        check();
    }

    @Scheduled(initialDelayString = "${labs64.reporting.row-policy-recheck:PT5M}",
            fixedDelayString = "${labs64.reporting.row-policy-recheck:PT5M}")
    public void check() {
        Set<String> found = new TreeSet<>();
        catalogues.all().forEach((tenant, catalogue) -> {
            for (String table : tables(catalogue)) {
                if (readableWithoutPolicy(catalogue.database(), table)) {
                    found.add(tenant + ":" + catalogue.database() + "." + table);
                }
            }
        });
        if (!found.isEmpty()) {
            LOG.error("The reader sees rows of a scope nobody has, so these tables have no working row policy: {}", found);
        }
        unprotected = Set.copyOf(found);
    }

    @Override
    public Health health() {
        Health.Builder health = strict && !unprotected.isEmpty() ? Health.outOfService() : Health.up();
        return unprotected.isEmpty() ? health.build() : health.withDetail("unprotectedTables", unprotected).build();
    }

    private boolean readableWithoutPolicy(String database, String table) {
        try {
            return !clickHouse.query(database, PROBE_SCOPE, new SqlQuery("SELECT 1 AS probe FROM " + table + " LIMIT 1",
                    java.util.Map.of())).isEmpty();
        } catch (ReportingException e) {
            LOG.warn("Could not check the row policy of {}.{}: {}", database, table, e.getMessage());
            return false;
        }
    }

    private static Set<String> tables(Catalogue catalogue) {
        Set<String> tables = new TreeSet<>();
        catalogue.metrics().values().forEach(metric -> tables.add(metric.table()));
        if (catalogue.events() != null) {
            tables.add(catalogue.events().table());
        }
        catalogue.trace().forEach(source -> tables.add(source.table()));
        if (catalogue.status() != null) {
            tables.add(catalogue.status().table());
        }
        return tables;
    }
}
