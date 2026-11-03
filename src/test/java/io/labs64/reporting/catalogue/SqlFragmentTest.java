package io.labs64.reporting.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlFragmentTest {

    /** The shapes real catalogues use: if one of these is refused, a working catalogue stops the rollout. */
    @ParameterizedTest
    @ValueSource(strings = { "count()", "sum(requests)", "countIf(response_status >= 400)", "toString(event_id)",
            "concat('transaction.', toString(status))", "status = 'closed' AND source = 'SHOP' AND gross_amount IS NOT NULL",
            "action IN ('a', 'b')", "action NOT IN ('a')", "x IN  ('a')", "status = 'SELECT; -- from (' ", "name = 'it''s'",
            "name = 'it\\'s ('", "`order` = 1", "`from`", "\"group\"", "fromUnixTimestamp(t)", "formatDateTime(t, '%F')",
            "format('{}-{}', a, b)", "format ('{}', a)",
            "selected", "url", "file_name", "order_id", "toStartOfDay(t) + INTERVAL 1 DAY" })
    void acceptsAnExpressionOverTheTablesColumns(String fragment) {
        assertThat(SqlFragment.check(fragment)).isEqualTo(fragment);
    }

    @ParameterizedTest
    @ValueSource(strings = { "a = 1) OR (1 = 1", "a = 1) --", ")", "(", "((a)", "a; DROP TABLE t", "a -- rest", "a /* rest */",
            "a # rest", "{scope:String}", "(SELECT 1)", "(select 1)", "a IN (SELECT b FROM t)", "a FROM other", "a UNION ALL b",
            "a JOIN b", "a WITH b", "a GROUP BY b", "a ORDER BY b", "a LIMIT 1", "a SETTINGS x = 1", "a FORMAT CSV", "a INTO OUTFILE 'f'",
            "a IN other_table", "a NOT IN other", "a IN", "url('http://x', CSV)", "file ('f')", "remote('h', d.t)",
            "s3('b')", "dictGet('d', 'a', k)", "dictGetString('d', 'a', k)", "'open", "`open", "a = 'x\\'" })
    void refusesWhatCouldEscapeItsPlace(String fragment) {
        assertThatThrownBy(() -> SqlFragment.check(fragment)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(fragment);
    }
}
