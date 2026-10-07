package com.aurora.guestops.commons.guardrails;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Masks personal data in free text and strips identity fields before data leaves the trust boundary. */
@Component
public class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // Card-like numbers: 13-19 digits, optionally separated by spaces or dashes.
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    // North American (555-123-4567) and international (+44 20 7946 0958) formats; not dates like 2026-10-07.
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w-])(?:\\(?\\d{3}\\)?[ .-]?\\d{3}[ .-]\\d{4}|\\+\\d{1,3}[ .-]?\\d{2,4}[ .-]?\\d{3,4}[ .-]?\\d{3,4})\\b");
    private static final Set<String> IDENTITY_FIELDS = Set.of(
            "guestName", "firstName", "lastName", "email", "phone", "loyaltyNumber", "address", "paymentCard");

    public record Redaction(String text, boolean changed) {
    }

    public Redaction redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Redaction(text, false);
        }
        String out = EMAIL.matcher(text).replaceAll("[email]");
        out = CARD.matcher(out).replaceAll("[card-number]");
        out = PHONE.matcher(out).replaceAll("[phone]");
        return new Redaction(out, !out.equals(text));
    }

    /** For PARTNER_SAFE agents: drop identity fields entirely and mask anything left in free text. */
    public Map<String, Object> partnerSafe(Map<String, Object> context) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (context == null) {
            return out;
        }
        context.forEach((k, v) -> {
            if (IDENTITY_FIELDS.contains(k)) {
                return;
            }
            out.put(k, v instanceof String s ? redact(s).text() : v);
        });
        return out;
    }
}
