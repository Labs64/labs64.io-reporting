package io.labs64.reporting.catalogue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import io.labs64.reporting.ReportingProperties;
import io.labs64.reporting.catalogue.Catalogue.Bodies;
import io.labs64.reporting.catalogue.Catalogue.EventSource;
import io.labs64.reporting.catalogue.Catalogue.Metric;
import io.labs64.reporting.catalogue.Catalogue.Status;

/**
 * One catalogue per tenant: {@code <directory>/<tenant>.yaml}. Read once at start. An invalid file fails the start,
 * so a rollout stops instead of serving wrong numbers.
 */
@Component
public class CatalogueRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogueRegistry.class);
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final Map<String, Catalogue> catalogues;

    @Autowired
    public CatalogueRegistry(ReportingProperties properties) {
        this(load(properties.catalogueDirectory()));
    }

    public CatalogueRegistry(Map<String, Catalogue> catalogues) {
        this.catalogues = Map.copyOf(catalogues);
        LOG.info("Reporting catalogues loaded: {}", this.catalogues.keySet());
    }

    public Optional<Catalogue> forTenant(String tenant) {
        return Optional.ofNullable(catalogues.get(tenant));
    }

    static Map<String, Catalogue> load(Path directory) {
        Map<String, Catalogue> result = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return result;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String tenant = file.getFileName().toString().replaceFirst("\\.yaml$", "");
                try (Reader reader = Files.newBufferedReader(file)) {
                    result.put(tenant, parse(new Yaml().load(reader)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Catalogue " + file + ": " + e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the catalogues in " + directory, e);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Catalogue parse(Object document) {
        Map<String, Object> root = map(document, "catalogue");
        String database = identifier(root, "database");

        Map<String, Metric> metrics = new LinkedHashMap<>();
        map(root.get("metrics"), "metrics").forEach((name, value) -> metrics.put(name, metric(name, map(value, name))));

        EventSource events = root.get("events") == null ? null : source("events", map(root.get("events"), "events"));
        List<EventSource> trace = new ArrayList<>();
        if (root.get("trace") != null) {
            for (Object entry : (List<Object>) root.get("trace")) {
                Map<String, Object> source = map(entry, "trace");
                trace.add(source(required(source, "name"), source));
            }
        } else if (events != null) {
            trace.add(events);
        }

        Status status = null;
        if (root.get("status") != null) {
            Map<String, Object> s = map(root.get("status"), "status");
            status = new Status(identifier(s, "table"), identifier(s, "scopeColumn"), required(s, "received"));
        }
        Bodies bodies = null;
        if (root.get("bodies") != null) {
            Map<String, Object> b = map(root.get("bodies"), "bodies");
            bodies = new Bodies(required(b, "group"), required(b, "keyTemplate"));
        }
        return new Catalogue(database, Map.copyOf(metrics), events, List.copyOf(trace), status, bodies);
    }

    private static Metric metric(String name, Map<String, Object> m) {
        return new Metric(name, (String) m.get("description"), required(m, "group"), identifier(m, "table"),
                identifier(m, "scopeColumn"), required(m, "time"), required(m, "value"),
                Boolean.TRUE.equals(m.get("fullHistory")), Boolean.TRUE.equals(m.get("final")), (String) m.get("where"),
                strings(m.get("dimensions")), strings(m.get("filters")));
    }

    private static EventSource source(String name, Map<String, Object> s) {
        Map<String, String> columns = strings(s.get("columns"));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(name + ": needs columns");
        }
        columns.keySet().forEach(CatalogueRegistry::requireIdentifier);
        return new EventSource(name, identifier(s, "table"), identifier(s, "scopeColumn"), required(s, "time"),
                required(s, "id"), required(s, "correlation"), Boolean.TRUE.equals(s.get("final")),
                (String) s.get("where"), columns, strings(s.get("filters")));
    }

    private static String identifier(Map<String, Object> map, String key) {
        return requireIdentifier(required(map, key));
    }

    private static String requireIdentifier(String value) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("'" + value + "' is not an identifier");
        }
        return value;
    }

    private static String required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        return text;
    }

    private static Map<String, String> strings(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value != null) {
            map(value, "map").forEach((k, v) -> result.put(k, String.valueOf(v)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(what + " must be a mapping");
        }
        return (Map<String, Object>) value;
    }
}
