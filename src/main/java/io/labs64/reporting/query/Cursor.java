package io.labs64.reporting.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

import io.labs64.reporting.ReportingException;

/** Where a page ended: opaque to the caller, and checked, because it comes back as request input. */
public record Cursor(String time, String id) {

    private static final Pattern TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8).split("\\|", 2);
            if (parts.length == 2 && TIME.matcher(parts[0]).matches() && ID.matcher(parts[1]).matches()) {
                return new Cursor(parts[0], parts[1]);
            }
        } catch (IllegalArgumentException e) {
            // answered below
        }
        throw ReportingException.badRequest("Invalid cursor.");
    }
}
