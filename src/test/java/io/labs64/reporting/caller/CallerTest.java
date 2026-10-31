package io.labs64.reporting.caller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.labs64.authcontext.core.AuthContext;
import io.labs64.reporting.ReportingException;

class CallerTest {

    private static AuthContext context(String tenant, String... scopes) {
        return new AuthContext("service:backend", tenant, Set.of(scopes), "r");
    }

    @Test
    void readsTheDelegatedValues() {
        Caller caller = Caller.of(context("demo", Caller.DELEGATION_SCOPE), "V1", " base, paid ,", 30);
        assertThat(caller.tenant()).isEqualTo("demo");
        assertThat(caller.groups()).containsExactlyInAnyOrder("base", "paid");
        assertThat(caller.historyDays()).isEqualTo(30);
    }

    @Test
    void refusesACallerWithoutTheDelegationScope() {
        assertThatThrownBy(() -> Caller.of(context("demo", "audit-event:write"), "V1", null, null))
                .isInstanceOf(ReportingException.class).hasMessageContaining("may not read");
    }

    @Test
    void refusesACallerWithoutATenant() {
        assertThatThrownBy(() -> Caller.of(context(null, Caller.DELEGATION_SCOPE), "V1", null, null))
                .hasMessageContaining("no tenant");
        assertThatThrownBy(() -> Caller.of(context("../other", Caller.DELEGATION_SCOPE), "V1", null, null))
                .hasMessageContaining("no tenant");
    }

    @Test
    void refusesAnInvalidScope() {
        for (String scope : new String[] { null, "", "a b", "a'b", "x".repeat(129) }) {
            assertThatThrownBy(() -> Caller.of(context("demo", Caller.DELEGATION_SCOPE), scope, null, null))
                    .hasMessageContaining("X-Reporting-Scope");
        }
    }

    @Test
    void noGroupsMeansNoMetrics() {
        assertThat(Caller.of(context("demo", Caller.DELEGATION_SCOPE), "V1", null, null).groups()).isEmpty();
    }
}
