package com.aurora.guestops.commons.guardrails;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Checks the final answer before staff see it: masks any personal data that slipped through, and
 * rewrites false claims that a high-impact action was completed when it is only pending approval.
 */
@Component
public class OutputGuardrail {

    public record Result(String text, List<String> violations) {
    }

    // "I have moved / credited / refunded / booked ..." claims; such actions only run after approval.
    private static final Pattern FALSE_COMPLETION = Pattern.compile(
            "\\b(I|we)\\s+(have\\s+|'ve\\s+)?(already\\s+)?(moved|credited|refunded|comped|waived|upgraded|booked|extended)\\b",
            Pattern.CASE_INSENSITIVE);

    private final PiiRedactor redactor;

    public OutputGuardrail(PiiRedactor redactor) {
        this.redactor = redactor;
    }

    public Result check(String answer, boolean actionsExecutedThisTurn) {
        List<String> violations = new ArrayList<>();
        PiiRedactor.Redaction r = redactor.redact(answer);
        String text = r.text();
        if (r.changed()) {
            violations.add("pii_masked");
        }
        if (!actionsExecutedThisTurn) {
            Matcher m = FALSE_COMPLETION.matcher(text);
            if (m.find()) {
                violations.add("unapproved_completion_claim");
                text = m.replaceAll(mr -> mr.group(1) + " have proposed (pending manager approval) to "
                        + baseVerb(mr.group(4)));
            }
        }
        return new Result(text, violations);
    }

    private static String baseVerb(String pastTense) {
        return switch (pastTense.toLowerCase()) {
            case "moved" -> "move";
            case "credited" -> "credit";
            case "refunded" -> "refund";
            case "comped" -> "comp";
            case "waived" -> "waive";
            case "upgraded" -> "upgrade";
            case "booked" -> "book";
            case "extended" -> "extend";
            default -> pastTense;
        };
    }
}
