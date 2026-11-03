package io.labs64.reporting.catalogue;

import java.util.Locale;
import java.util.Set;

/**
 * Keeps a catalogue's SQL fragment inside its place in the query. A catalogue is reviewed configuration, so this guards
 * against a mistake, not an attacker: a {@code where} of {@code a) OR (b} would otherwise lift the scope filter around
 * it. A fragment is one expression over the table's own columns: it can't end the statement, comment out the rest, or
 * read anything else. Text in quotes is data and is skipped. A column named like a keyword needs backticks.
 */
final class SqlFragment {

    /** What would add a clause or a statement to the query around the fragment. */
    private static final Set<String> KEYWORDS = Set.of("select", "from", "join", "union", "intersect", "except", "with",
            "where", "having", "group", "order", "limit", "offset", "settings", "format", "into", "insert", "alter",
            "create", "drop", "truncate", "attach", "detach", "grant", "revoke", "system", "kill");

    /** Functions that reach outside the table. */
    private static final Set<String> FUNCTIONS = Set.of("file", "url", "remote", "remotesecure", "cluster",
            "clusterallreplicas", "s3", "s3cluster", "gcs", "azureblobstorage", "hdfs", "mysql", "postgresql", "mongodb",
            "sqlite", "odbc", "jdbc", "redis", "executable", "input", "merge", "dictionary");

    private SqlFragment() {
    }

    /** @return the fragment, when it is safe to place in a query */
    static String check(String fragment) {
        int depth = 0;
        int i = 0;
        while (i < fragment.length()) {
            char c = fragment.charAt(i);
            if (c == '\'' || c == '`' || c == '"') {
                i = closing(fragment, i) + 1;
            } else if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < fragment.length()
                        && (Character.isLetterOrDigit(fragment.charAt(end)) || fragment.charAt(end) == '_')) {
                    end++;
                }
                checkWord(fragment, fragment.substring(i, end).toLowerCase(Locale.ROOT), end);
                i = end;
            } else {
                if (c == ';' || c == '#' || c == '{' || c == '}' || fragment.startsWith("--", i)
                        || fragment.startsWith("/*", i)) {
                    throw refused(fragment, "ends the statement, comments it out or names a parameter");
                }
                depth += c == '(' ? 1 : c == ')' ? -1 : 0;
                if (depth < 0) {
                    throw refused(fragment, "closes a parenthesis it did not open");
                }
                i++;
            }
        }
        if (depth != 0) {
            throw refused(fragment, "leaves a parenthesis open");
        }
        return fragment;
    }

    private static void checkWord(String fragment, String word, int end) {
        char next = nextVisible(fragment, end);
        // format(...) is a plain function; only the FORMAT clause names an output format.
        if (KEYWORDS.contains(word) && !(word.equals("format") && next == '(')) {
            throw refused(fragment, "uses the keyword " + word);
        }
        if (next == '(' && (FUNCTIONS.contains(word) || word.startsWith("dictget"))) {
            throw refused(fragment, "reaches outside the table with " + word);
        }
        // x IN other_table reads another table; x IN (1, 2) stays a list, and a subquery there is refused by SELECT.
        if (word.equals("in") && next != '(') {
            throw refused(fragment, "uses IN with anything but a list");
        }
    }

    private static char nextVisible(String fragment, int from) {
        for (int i = from; i < fragment.length(); i++) {
            if (!Character.isWhitespace(fragment.charAt(i))) {
                return fragment.charAt(i);
            }
        }
        return 0;
    }

    /** The index of the quote closing the one at {@code open}: a backslash escapes, and so does doubling the quote. */
    private static int closing(String fragment, int open) {
        char quote = fragment.charAt(open);
        for (int i = open + 1; i < fragment.length(); i++) {
            char c = fragment.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                if (i + 1 < fragment.length() && fragment.charAt(i + 1) == quote) {
                    i++;
                } else {
                    return i;
                }
            }
        }
        throw refused(fragment, "leaves a quote open");
    }

    private static IllegalArgumentException refused(String fragment, String why) {
        return new IllegalArgumentException("SQL fragment '" + fragment + "' " + why);
    }
}
