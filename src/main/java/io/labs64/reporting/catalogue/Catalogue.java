package io.labs64.reporting.catalogue;

import java.util.List;
import java.util.Map;

/**
 * What one tenant offers. SQL fragments here are reviewed configuration, never request input: a request only picks
 * names out of these maps.
 */
public record Catalogue(String database, Map<String, Metric> metrics, EventSource events, List<EventSource> trace,
        Status status, Bodies bodies) {

    /** A value aggregated over one table. */
    public record Metric(String name, String description, String group, String table, String scopeColumn, String time,
            String value, boolean fullHistory, boolean useFinal, String where, Map<String, String> dimensions,
            Map<String, String> filters) {
    }

    /** A table of single events: its output columns and what a search may filter on. */
    public record EventSource(String name, String table, String scopeColumn, String time, String id,
            String correlation, boolean useFinal, String where, Map<String, String> columns,
            Map<String, String> filters) {
    }

    /** The table and expression that say how fresh a customer's data is. */
    public record Status(String table, String scopeColumn, String received) {
    }

    /**
     * Where one event's archived bodies are, either an object per event or a folder of batches to search. Null: this
     * tenant has none.
     */
    public record Bodies(String group, String keyTemplate, String prefixTemplate) {
    }
}
