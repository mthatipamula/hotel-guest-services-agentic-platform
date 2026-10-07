package com.aurora.guestops.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * Java implementation of the four core Ragas metrics, using the same definitions as the Ragas
 * library (https://docs.ragas.io), with the judge LLM and Vertex AI embeddings:
 * <ul>
 *   <li>faithfulness: share of the answer's claims supported by the retrieved contexts</li>
 *   <li>answer relevancy: mean cosine similarity between the question and questions regenerated
 *       from the answer</li>
 *   <li>context precision: average precision of the ranked contexts against the reference answer</li>
 *   <li>context recall: share of the reference answer's statements attributable to the contexts</li>
 * </ul>
 * The runner also exports a Ragas-format JSONL file so the Python library can score the same run.
 */
@Component
public class RagasMetrics {

    private static final int MAX_CONTEXTS = 8;

    private final JudgeLlm judge;
    private final EmbeddingModel embeddings;

    public RagasMetrics(JudgeLlm judge, EmbeddingModel embeddings) {
        this.judge = judge;
        this.embeddings = embeddings;
    }

    record Claims(List<String> claims) {
    }

    record Verdict(String statement, boolean supported, String reason) {
    }

    record Verdicts(List<Verdict> verdicts) {
    }

    record GeneratedQuestions(List<String> questions, boolean noncommittal) {
    }

    record ContextUseful(int index, boolean useful) {
    }

    record ContextUsefulness(List<ContextUseful> contexts) {
    }

    public Map<String, Object> evaluate(String question, String answer, List<String> contexts, String groundTruth) {
        List<String> ctx = contexts.stream().filter(s -> s != null && !s.isBlank()).limit(MAX_CONTEXTS).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("faithfulness", faithfulness(answer, ctx));
        out.put("answerRelevancy", answerRelevancy(question, answer));
        out.put("contextPrecision", contextPrecision(question, ctx, groundTruth));
        out.put("contextRecall", contextRecall(ctx, groundTruth));
        out.put("contextsUsed", ctx.size());
        return out;
    }

    Double faithfulness(String answer, List<String> contexts) {
        if (contexts.isEmpty()) {
            return null;
        }
        Claims claims = judge.ask("""
                Break the ANSWER into short, self-contained factual claims (no pronouns, one fact each).
                Ignore greetings, advice phrasing and questions back to the user. At most 12 claims.""",
                "ANSWER:\n" + answer, Claims.class);
        if (claims == null || claims.claims() == null || claims.claims().isEmpty()) {
            return null;
        }
        Verdicts v = judge.ask("""
                For each STATEMENT decide if it can be directly inferred from the CONTEXT.
                supported=true only if the context supports it; a statement that merely restates the request or
                recommends a next step without asserting a fact counts as supported.""",
                "CONTEXT:\n" + join(contexts) + "\n\nSTATEMENTS:\n" + numbered(claims.claims()), Verdicts.class);
        return ratio(v);
    }

    Double answerRelevancy(String question, String answer) {
        GeneratedQuestions g = judge.ask("""
                Generate 3 different questions that the ANSWER would be a direct answer to.
                Set noncommittal=true if the answer is evasive or vague ("I'm not sure", "it depends").""",
                "ANSWER:\n" + answer, GeneratedQuestions.class);
        if (g == null || g.questions() == null || g.questions().isEmpty()) {
            return null;
        }
        if (g.noncommittal()) {
            return 0.0;
        }
        float[] q = embeddings.embed(question);
        double sum = 0;
        for (String generated : g.questions()) {
            sum += cosine(q, embeddings.embed(generated));
        }
        return round(sum / g.questions().size());
    }

    Double contextPrecision(String question, List<String> contexts, String groundTruth) {
        if (contexts.isEmpty() || groundTruth == null) {
            return null;
        }
        ContextUsefulness u = judge.ask("""
                For each numbered CONTEXT, decide whether it was useful in arriving at the REFERENCE ANSWER to the
                QUESTION. Return one entry per context with its index.""",
                "QUESTION:\n" + question + "\n\nREFERENCE ANSWER:\n" + groundTruth + "\n\nCONTEXTS:\n"
                        + numbered(contexts), ContextUsefulness.class);
        if (u == null || u.contexts() == null) {
            return null;
        }
        boolean[] useful = new boolean[contexts.size()];
        u.contexts().forEach(c -> {
            int i = c.index() - 1;
            if (i >= 0 && i < useful.length) {
                useful[i] = c.useful();
            }
        });
        // Average precision@k over the relevant positions (Ragas context_precision).
        double sum = 0;
        int relevantSoFar = 0;
        for (int k = 0; k < useful.length; k++) {
            if (useful[k]) {
                relevantSoFar++;
                sum += (double) relevantSoFar / (k + 1);
            }
        }
        return relevantSoFar == 0 ? 0.0 : round(sum / relevantSoFar);
    }

    Double contextRecall(List<String> contexts, String groundTruth) {
        if (contexts.isEmpty() || groundTruth == null) {
            return null;
        }
        List<String> sentences = new ArrayList<>();
        for (String s : groundTruth.split("(?<=[.!?])\\s+")) {
            if (!s.isBlank()) {
                sentences.add(s.trim());
            }
        }
        Verdicts v = judge.ask("""
                For each STATEMENT from a reference answer, decide whether it can be attributed to the CONTEXT
                (supported=true) or not.""",
                "CONTEXT:\n" + join(contexts) + "\n\nSTATEMENTS:\n" + numbered(sentences), Verdicts.class);
        return ratio(v);
    }

    private static Double ratio(Verdicts v) {
        if (v == null || v.verdicts() == null || v.verdicts().isEmpty()) {
            return null;
        }
        long ok = v.verdicts().stream().filter(Verdict::supported).count();
        return round((double) ok / v.verdicts().size());
    }

    private static String join(List<String> contexts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contexts.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(contexts.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static String numbered(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(i + 1).append(". ").append(items.get(i)).append('\n');
        }
        return sb.toString();
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
