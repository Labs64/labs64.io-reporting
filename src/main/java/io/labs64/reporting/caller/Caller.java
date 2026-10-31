package io.labs64.reporting.caller;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.labs64.authcontext.core.AuthContext;
import io.labs64.reporting.ReportingException;

/**
 * Who a report is for. The tenant comes from the gateway's trusted context. The scope, groups and history come from
 * the X-Reporting-* headers, which count only because the caller holds the delegation scope: only a tenant's backend
 * gets that, after it authenticated its own customer. A request can't name another tenant, and no request body can
 * name a scope.
 */
public record Caller(String tenant, String scope, Set<String> groups, Integer historyDays) {

    public static final String DELEGATION_SCOPE = "reporting:read-delegated";

    private static final Pattern TENANT = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
    private static final Pattern SCOPE = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

    public static Caller of(AuthContext context, String scope, String groups, Integer historyDays) {
        if (!context.hasScope(DELEGATION_SCOPE)) {
            throw ReportingException.forbidden("The caller may not read reports on behalf of a customer.");
        }
        String tenant = context.tenantId();
        if (tenant == null || !TENANT.matcher(tenant).matches()) {
            throw ReportingException.forbidden("The caller belongs to no tenant.");
        }
        if (scope == null || !SCOPE.matcher(scope).matches()) {
            throw ReportingException.badRequest("X-Reporting-Scope is missing or invalid.");
        }
        if (historyDays != null && historyDays < 1) {
            throw ReportingException.badRequest("X-Reporting-History-Days must be at least 1.");
        }
        Set<String> parsed = groups == null ? Set.of()
                : Arrays.stream(groups.split(",")).map(String::trim).filter(g -> !g.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        return new Caller(tenant, scope, parsed, historyDays);
    }
}
