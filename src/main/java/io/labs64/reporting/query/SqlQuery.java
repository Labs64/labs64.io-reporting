package io.labs64.reporting.query;

import java.util.Map;

/** SQL from the catalogue, and the request's values as server-side bound parameters. */
public record SqlQuery(String sql, Map<String, String> parameters) {
}
