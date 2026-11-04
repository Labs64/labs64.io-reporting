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

    /** Every tenant's catalogue, by tenant. */
    public Map<String, Catalogue> all() {
        return catalogues;
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
            status = new Status(identifier(s, "table"), identifier(s, "scopeColumn"), expression(s, "received"));
        }
        Bodies bodies = null;
        if (root.get("bodies") != null) {
            Map<String, Object> b = map(root.get("bodies"), "bodies");
            long ways = Stream.of("keyTemplate", "prefixTemplate", "keyColumn").filter(b::containsKey).count();
            if (ways != 1) {
                throw new IllegalArgumentException("bodies: needs exactly one of 'keyTemplate', 'prefixTemplate', 'keyColumn'");
            }
            if (b.containsKey("keyPattern") && !b.containsKey("keyColumn")) {
                throw new IllegalArgumentException("bodies: 'keyPattern' only applies with 'keyColumn'");
            }
            String group = b.get("group") == null ? null : required(b, "group");
            bodies = new Bodies(group,
                    b.containsKey("keyTemplate") ? required(b, "keyTemplate") : null,
                    b.containsKey("prefixTemplate") ? required(b, "prefixTemplate") : null,
                    b.containsKey("keyColumn") ? expression(b, "keyColumn") : null,
                    b.containsKey("keyPattern") ? required(b, "keyPattern") : null,
                    paths(b.get("exclude")));
        }
        return new Catalogue(database, Map.copyOf(metrics), events, List.copyOf(trace), status, bodies,
                maxHistoryDays(root.get("maxHistoryDays")));
    }

    private static Integer maxHistoryDays(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer days) || days < 1) {
            throw new IllegalArgumentException("'maxHistoryDays' must be a whole number of at least 1");
        }
        return days;
    }

    private static Metric metric(String name, Map<String, Object> m) {
        return new Metric(name, (String) m.get("description"), required(m, "group"), identifier(m, "table"),
                identifier(m, "scopeColumn"), expression(m, "time"), expression(m, "value"),
                Boolean.TRUE.equals(m.get("fullHistory")), Boolean.TRUE.equals(m.get("final")), optionalExpression(m, "where"),
                expressions(m.get("dimensions")), expressions(m.get("filters")));
    }

    private static EventSource source(String name, Map<String, Object> s) {
        Map<String, String> columns = expressions(s.get("columns"));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(name + ": needs columns");
        }
        columns.keySet().forEach(CatalogueRegistry::requireIdentifier);
        return new EventSource(name, identifier(s, "table"), identifier(s, "scopeColumn"), expression(s, "time"),
                expression(s, "id"), expression(s, "correlation"), Boolean.TRUE.equals(s.get("final")),
                optionalExpression(s, "where"), columns, expressions(s.get("filters")));
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

    private static String expression(Map<String, Object> map, String key) {
        return SqlFragment.check(required(map, key));
    }

    private static String optionalExpression(Map<String, Object> map, String key) {
        return map.get(key) == null ? null : expression(map, key);
    }

    private static Map<String, String> expressions(Object value) {
        Map<String, String> result = strings(value);
        result.values().forEach(SqlFragment::check);
        return result;
    }

    /** Dotted paths into an archived event, e.g. {@code extra.planId}: identifiers joined by dots. */
    private static List<String> paths(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("bodies.exclude must be a list");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String path = String.valueOf(item);
            for (String part : path.split("\\.", -1)) {
                requireIdentifier(part);
            }
            result.add(path);
        }
        return List.copyOf(result);
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
