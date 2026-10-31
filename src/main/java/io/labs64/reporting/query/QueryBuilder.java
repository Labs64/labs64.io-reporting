package io.labs64.reporting.query;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import io.labs64.reporting.ReportingException;
import io.labs64.reporting.catalogue.Catalogue.EventSource;
import io.labs64.reporting.catalogue.Catalogue.Metric;

/**
 * Turns a request into SQL. Only catalogue fragments become SQL text; every value of a request is a bound parameter.
 * The scope is always filtered here, and again by the row policy on the server.
 */
public final class QueryBuilder {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private QueryBuilder() {
    }

    public static SqlQuery metric(Metric metric, String scope, OffsetDateTime from, OffsetDateTime to, String interval,
            List<String> groupBy, Map<String, List<String>> filters) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("scope", scope);
        parameters.put("from", time(from));
        parameters.put("to", time(to));

        List<String> select = new ArrayList<>();
        List<String> group = new ArrayList<>();
        String bucket = bucket(metric.time(), interval);
        if (bucket != null) {
            select.add("toString(" + bucket + ") AS time");
            group.add("time");
        }
        for (int i = 0; i < groupBy.size(); i++) {
            String expression = metric.dimensions().get(groupBy.get(i));
            if (expression == null) {
                throw ReportingException.badRequest("Unknown dimension: " + groupBy.get(i));
            }
            select.add("toString(" + expression + ") AS d" + i);
            group.add("d" + i);
        }
        select.add(metric.value() + " AS value");

        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", select)).append(" FROM ")
                .append(metric.table());
        if (metric.useFinal()) {
            sql.append(" FINAL");
        }
        sql.append(" WHERE ").append(metric.scopeColumn()).append(" = {scope:String}");
        sql.append(" AND ").append(metric.time()).append(" >= {from:DateTime64(3, 'UTC')}");
        sql.append(" AND ").append(metric.time()).append(" < {to:DateTime64(3, 'UTC')}");
        if (metric.where() != null) {
            sql.append(" AND (").append(metric.where()).append(")");
        }
        appendFilters(sql, parameters, metric.filters(), filters);
        if (!group.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", group));
            sql.append(" ORDER BY ").append(String.join(", ", group));
        }
        return new SqlQuery(sql.toString(), parameters);
    }

    /** One page, newest first. One row more than {@code limit} is asked for: it says another page exists. */
    public static SqlQuery events(EventSource source, String scope, OffsetDateTime from, OffsetDateTime to,
            Map<String, List<String>> filters, int limit, Cursor cursor) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("scope", scope);
        parameters.put("from", time(from));
        parameters.put("to", time(to));

        StringBuilder sql = select(source);
        sql.append(" AND ").append(source.time()).append(" >= {from:DateTime64(3, 'UTC')}");
        sql.append(" AND ").append(source.time()).append(" < {to:DateTime64(3, 'UTC')}");
        appendFilters(sql, parameters, source.filters(), filters);
        if (cursor != null) {
            parameters.put("cursorTime", cursor.time());
            parameters.put("cursorId", cursor.id());
            sql.append(" AND (").append(source.time()).append(", toString(").append(source.id())
                    .append(")) < ({cursorTime:DateTime64(3, 'UTC')}, {cursorId:String})");
        }
        sql.append(" ORDER BY ").append(source.time()).append(" DESC, toString(").append(source.id()).append(") DESC");
        sql.append(" LIMIT ").append(limit + 1);
        return new SqlQuery(sql.toString(), parameters);
    }

    /** @param earliest the start of the caller's history; null: all of it */
    public static SqlQuery event(EventSource source, String scope, String eventId, OffsetDateTime earliest) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("scope", scope);
        parameters.put("eventId", eventId);
        StringBuilder sql = select(source);
        appendEarliest(sql, parameters, source, earliest);
        sql.append(" AND toString(").append(source.id()).append(") = {eventId:String} LIMIT 1");
        return new SqlQuery(sql.toString(), parameters);
    }

    /** @param earliest the start of the caller's history; null: all of it */
    public static SqlQuery trace(EventSource source, String scope, String correlationId, int limit,
            OffsetDateTime earliest) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("scope", scope);
        parameters.put("correlationId", correlationId);
        StringBuilder sql = select(source);
        appendEarliest(sql, parameters, source, earliest);
        sql.append(" AND ").append(source.correlation()).append(" = {correlationId:String}");
        sql.append(" ORDER BY ").append(source.time()).append(" LIMIT ").append(limit);
        return new SqlQuery(sql.toString(), parameters);
    }

    /** The receipt time of the newest event of this scope, as text. */
    public static SqlQuery dataAsOf(String table, String scopeColumn, String received, String scope) {
        return new SqlQuery("SELECT toString(max(" + received + ")) AS dataAsOf, count() AS rows FROM " + table
                + " WHERE " + scopeColumn + " = {scope:String}", Map.of("scope", scope));
    }

    private static StringBuilder select(EventSource source) {
        StringJoiner columns = new StringJoiner(", ");
        source.columns().forEach((name, expression) -> columns.add(expression + " AS " + name));
        StringBuilder sql = new StringBuilder("SELECT ").append(columns).append(" FROM ").append(source.table());
        if (source.useFinal()) {
            sql.append(" FINAL");
        }
        sql.append(" WHERE ").append(source.scopeColumn()).append(" = {scope:String}");
        if (source.where() != null) {
            sql.append(" AND (").append(source.where()).append(")");
        }
        return sql;
    }

    private static void appendEarliest(StringBuilder sql, Map<String, String> parameters, EventSource source,
            OffsetDateTime earliest) {
        if (earliest != null) {
            parameters.put("earliest", time(earliest));
            sql.append(" AND ").append(source.time()).append(" >= {earliest:DateTime64(3, 'UTC')}");
        }
    }

    private static void appendFilters(StringBuilder sql, Map<String, String> parameters, Map<String, String> allowed,
            Map<String, List<String>> requested) {
        if (requested == null) {
            return;
        }
        int index = 0;
        for (Map.Entry<String, List<String>> filter : requested.entrySet()) {
            String expression = allowed.get(filter.getKey());
            if (expression == null) {
                throw ReportingException.badRequest("Unknown filter: " + filter.getKey());
            }
            if (filter.getValue() == null || filter.getValue().isEmpty()) {
                throw ReportingException.badRequest("Filter has no value: " + filter.getKey());
            }
            String name = "filter" + index++;
            parameters.put(name, arrayLiteral(filter.getValue()));
            sql.append(" AND toString(").append(expression).append(") IN {").append(name).append(":Array(String)}");
        }
    }

    /** {@code day} and so on on a UTC clock; null when no time axis is wanted. Weeks start on Monday. */
    private static String bucket(String time, String interval) {
        String utc = "toDateTime(" + time + ", 'UTC')";
        return switch (interval == null ? "day" : interval) {
            case "none" -> null;
            case "hour" -> "toStartOfHour(" + utc + ")";
            case "day" -> "toDate(" + utc + ")";
            case "week" -> "toMonday(" + utc + ")";
            case "month" -> "toStartOfMonth(" + utc + ")";
            case "year" -> "toStartOfYear(" + utc + ")";
            default -> throw ReportingException.badRequest("Unknown interval: " + interval);
        };
    }

    private static String time(OffsetDateTime value) {
        return TIME.format(value.withOffsetSameInstant(ZoneOffset.UTC));
    }

    static String arrayLiteral(List<String> values) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (String value : values) {
            array.add("'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'");
        }
        return array.toString();
    }
}
