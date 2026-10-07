package com.aurora.guestops.commons.guardrails;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * First line of defence, before any LLM call: length limit, known prompt-injection and data-exfiltration
 * phrasings, and PII masking. Deterministic, cheap and testable; the LLM safety agent is the second line.
 */
@Component
public class InputGuardrail {

    public record Verdict(boolean allowed, String reason, String sanitizedText, boolean piiMasked) {
    }

    private static final int MAX_CHARS = 2000;

    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(previous|prior|above|all|your|system)\\b.{0,25}\\b(instructions?|rules|prompts?|polic(y|ies))",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(reveal|show|print|repeat|dump)\\b.{0,30}\\b(system|hidden|initial|developer)\\s+(prompt|instructions?|message)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\byou are (now|no longer)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(developer|dan|jailbreak|god|admin)\\s+mode\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("</?\\s*(system|assistant|instructions?|tool)\\s*>", Pattern.CASE_INSENSITIVE));

    // Bulk extraction of guest data is never a legitimate single-guest service request.
    private static final List<Pattern> EXFILTRATION = List.of(
            Pattern.compile("\\b(all|every|list of|export|dump|download)\\b.{0,30}\\b(guests?|customers?|members?)\\b.{0,30}\\b(emails?|phones?|phone numbers|addresses|cards?|credit cards?|passports?|personal data|contact)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(credit card|card number|cvv|passport number)s?\\b.{0,30}\\b(for|of)\\b",
                    Pattern.CASE_INSENSITIVE));

    private final PiiRedactor redactor;

    public InputGuardrail(PiiRedactor redactor) {
        this.redactor = redactor;
    }

    public Verdict check(String message) {
        if (message == null || message.isBlank()) {
            return new Verdict(false, "empty_message", "", false);
        }
        if (message.length() > MAX_CHARS) {
            return new Verdict(false, "message_too_long", "", false);
        }
        for (Pattern p : INJECTION) {
            if (p.matcher(message).find()) {
                return new Verdict(false, "prompt_injection", "", false);
            }
        }
        for (Pattern p : EXFILTRATION) {
            if (p.matcher(message).find()) {
                return new Verdict(false, "data_exfiltration", "", false);
            }
        }
        PiiRedactor.Redaction r = redactor.redact(message);
        return new Verdict(true, null, r.text(), r.changed());
    }
}
