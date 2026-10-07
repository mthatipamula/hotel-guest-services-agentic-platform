package com.aurora.guestops.eval;

import com.aurora.guestops.commons.agent.ChatClients;
import com.aurora.guestops.commons.agent.ModelTier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.stereotype.Component;

/**
 * Spring AI's built-in LLM-as-a-judge evaluators, backed by the judge model:
 * FactCheckingEvaluator (is the answer supported by the retrieved documents?) and
 * RelevancyEvaluator (does the answer address the question, given the context?).
 */
@Component
public class SpringAiEvaluations {

    private final FactCheckingEvaluator factChecking;
    private final RelevancyEvaluator relevancy;

    public SpringAiEvaluations(ChatClients chatClients) {
        this.factChecking = FactCheckingEvaluator.builder(chatClients.builderForTier(ModelTier.JUDGE)).build();
        this.relevancy = new RelevancyEvaluator(chatClients.builderForTier(ModelTier.JUDGE));
    }

    public Map<String, Object> evaluate(String question, String answer, List<String> contexts) {
        List<Document> docs = contexts.stream().filter(c -> c != null && !c.isBlank()).limit(8)
                .map(Document::new).toList();
        EvaluationRequest request = new EvaluationRequest(question, docs, answer);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("factCheckingPass", docs.isEmpty() ? null : safe(() -> factChecking.evaluate(request)));
        out.put("relevancyPass", safe(() -> relevancy.evaluate(request)));
        return out;
    }

    private static Boolean safe(java.util.function.Supplier<EvaluationResponse> call) {
        try {
            return call.get().isPass();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
