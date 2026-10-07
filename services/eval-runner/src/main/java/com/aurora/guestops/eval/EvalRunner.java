package com.aurora.guestops.eval;

import com.aurora.guestops.eval.GoldenDataset.Case;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Runs the golden dataset through the orchestrator and scores every case three ways:
 * deterministic agent metrics, Ragas metrics (RAG quality) and LLM-as-a-judge (Spring AI
 * FactChecking/Relevancy evaluators plus a rubric). Aggregates are checked against quality gates.
 *
 * <p>Arguments: {@code --case=<id-prefix>} to run a subset.
 */
@Component
public class EvalRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final EvalProperties props;
    private final OrchestratorClient orchestrator;
    private final RagasMetrics ragas;
    private final SpringAiEvaluations springAi;
    private final RubricJudge rubric;
    private final JudgeLlm judge;
    private final ReportWriter writer;
    private final ObjectMapper mapper;
    private int exitCode;

    public EvalRunner(EvalProperties props, OrchestratorClient orchestrator, RagasMetrics ragas,
                      SpringAiEvaluations springAi, RubricJudge rubric, JudgeLlm judge, ReportWriter writer,
                      ObjectMapper mapper) {
        this.props = props;
        this.orchestrator = orchestrator;
        this.ragas = ragas;
        this.springAi = springAi;
        this.rubric = rubric;
        this.judge = judge;
        this.writer = writer;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) throws Exception {
        String filter = null;
        for (String a : args) {
            if (a.startsWith("--case=")) {
                filter = a.substring("--case=".length());
            }
        }
        GoldenDataset dataset;
        try (InputStream in = new DefaultResourceLoader().getResource(props.dataset()).getInputStream()) {
            dataset = mapper.readValue(in, GoldenDataset.class);
        }
        String f = filter;
        List<Case> cases = dataset.cases().stream().filter(c -> f == null || c.id().startsWith(f)).toList();
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Evaluating {} cases from {} against {} (judge: {})", cases.size(), dataset.name(),
                props.orchestratorUrl(), judge.model());

        List<Map<String, Object>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, props.concurrency()))) {
            List<Future<Map<String, Object>>> futures = cases.stream()
                    .map(c -> pool.submit(() -> evaluateCase(runId, c))).toList();
            for (Future<Map<String, Object>> fut : futures) {
                results.add(fut.get());
            }
        }

        Map<String, Object> summary = summarise(results);
        Map<String, Object> gates = gates(summary);
        boolean passed = gates.values().stream().allMatch(g -> Boolean.TRUE.equals(((Map<?, ?>) g).get("passed")));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("dataset", dataset.name());
        report.put("createdAt", Instant.now().toString());
        report.put("judgeModel", judge.model());
        report.put("judgeCalls", judge.calls());
        report.put("judgeTokens", judge.tokensUsed());
        report.put("passed", passed);
        report.put("summary", summary);
        report.put("gates", gates);
        report.put("cases", results);

        writer.write(report, results);
        if (props.publish()) {
            try {
                orchestrator.publish(report);
            } catch (RuntimeException e) {
                log.warn("Could not publish the report to the orchestrator: {}", e.toString());
            }
        }
        printConsole(summary, gates, passed);
        exitCode = props.failOnThreshold() && !passed ? 1 : 0;
    }

    private Map<String, Object> evaluateCase(String runId, Case c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", c.id());
        r.put("category", c.category());
        r.put("question", c.question());
        long start = System.nanoTime();
        try {
            JsonNode resp = orchestrator.chat(runId + "-" + c.id(), c.question(), c.confirmationNumber());
            String answer = resp.path("answer").asText("");
            List<String> contexts = new ArrayList<>();
            resp.path("contexts").forEach(n -> contexts.add(n.asText()));
            r.put("traceId", resp.path("traceId").asText());
            r.put("answer", answer);
            r.put("groundTruth", c.groundTruth());
            r.put("contexts", contexts);
            r.put("agent", AgentMetrics.evaluate(c, resp));
            if (!resp.path("blocked").asBoolean(false)) {
                if (c.hasGroundTruth()) {
                    r.put("ragas", ragas.evaluate(c.question(), answer, contexts, c.groundTruth()));
                }
                r.put("springAi", springAi.evaluate(c.question(), answer, contexts));
                r.put("judge", rubric.score(c.question(), answer, c.groundTruth()));
            }
            r.put("status", "ok");
        } catch (Exception e) {
            log.error("Case {} failed", c.id(), e);
            r.put("status", "error");
            r.put("error", e.toString());
        }
        r.put("evalSeconds", (System.nanoTime() - start) / 1_000_000_000.0);
        log.info("  {} done", c.id());
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summarise(List<Map<String, Object>> results) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("cases", results.size());
        s.put("errors", results.stream().filter(r -> "error".equals(r.get("status"))).count());
        Function<String, List<Object>> agent = k -> values(results, "agent", k);
        s.put("routingRecall", mean(agent.apply("routingRecall")));
        s.put("routingPrecision", mean(agent.apply("routingPrecision")));
        s.put("toolRecall", mean(agent.apply("toolRecall")));
        s.put("guardrailAccuracy", rate(agent.apply("guardrailCorrect")));
        s.put("approvalAccuracy", rate(agent.apply("approvalCorrect")));
        s.put("piiStrippedAccuracy", rate(agent.apply("piiStrippedCorrect")));
        s.put("contentAccuracy", rate(agent.apply("contentCorrect")));
        s.put("faithfulness", mean(values(results, "ragas", "faithfulness")));
        s.put("answerRelevancy", mean(values(results, "ragas", "answerRelevancy")));
        s.put("contextPrecision", mean(values(results, "ragas", "contextPrecision")));
        s.put("contextRecall", mean(values(results, "ragas", "contextRecall")));
        s.put("factCheckingPassRate", rate(values(results, "springAi", "factCheckingPass")));
        s.put("relevancyPassRate", rate(values(results, "springAi", "relevancyPass")));
        s.put("judgeHelpfulness", mean(values(results, "judge", "helpfulness")));
        s.put("judgePolicyCompliance", mean(values(results, "judge", "policyCompliance")));
        s.put("judgeTone", mean(values(results, "judge", "tone")));
        s.put("judgePrivacySafeRate", rate(values(results, "judge", "privacySafe")));
        List<Object> unapproved = values(results, "judge", "claimsUnapprovedActions");
        s.put("unapprovedActionClaims", unapproved.stream().filter(Boolean.TRUE::equals).count());
        List<Object> latencies = agent.apply("latencyMs");
        s.put("avgLatencyMs", mean(latencies));
        s.put("p95LatencyMs", percentile(latencies, 0.95));
        s.put("avgTokensPerRequest", mean(agent.apply("tokens")));
        s.put("totalAgentCostUsd", sum(agent.apply("costUsd")));
        s.put("routerFallbacks", agent.apply("routerFallback").stream().filter(Boolean.TRUE::equals).count());
        Map<String, Object> byCategory = new LinkedHashMap<>();
        results.stream().map(r -> (String) r.get("category")).distinct().forEach(cat -> {
            List<Map<String, Object>> sub = results.stream().filter(r -> cat.equals(r.get("category"))).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cases", sub.size());
            m.put("toolRecall", mean(values(sub, "agent", "toolRecall")));
            m.put("faithfulness", mean(values(sub, "ragas", "faithfulness")));
            m.put("judgeHelpfulness", mean(values(sub, "judge", "helpfulness")));
            byCategory.put(cat, m);
        });
        s.put("byCategory", byCategory);
        return s;
    }

    private Map<String, Object> gates(Map<String, Object> summary) {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("routing-recall", "routingRecall");
        keys.put("tool-recall", "toolRecall");
        keys.put("guardrail-accuracy", "guardrailAccuracy");
        keys.put("approval-accuracy", "approvalAccuracy");
        keys.put("faithfulness", "faithfulness");
        keys.put("answer-relevancy", "answerRelevancy");
        keys.put("context-precision", "contextPrecision");
        keys.put("context-recall", "contextRecall");
        keys.put("fact-checking-pass-rate", "factCheckingPassRate");
        keys.put("relevancy-pass-rate", "relevancyPassRate");
        keys.put("judge-helpfulness", "judgeHelpfulness");
        keys.put("judge-policy-compliance", "judgePolicyCompliance");
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Double> thresholds = props.thresholds() == null ? Map.of() : props.thresholds();
        keys.forEach((gate, metric) -> {
            Double threshold = thresholds.get(gate);
            Object actual = summary.get(metric);
            if (threshold == null || actual == null) {
                return;
            }
            double v = ((Number) actual).doubleValue();
            out.put(gate, Map.of("metric", metric, "threshold", threshold, "actual", v, "passed", v >= threshold));
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Object> values(List<Map<String, Object>> results, String section, String key) {
        return results.stream().map(r -> r.get(section)).filter(Objects::nonNull)
                .map(m -> ((Map<String, Object>) m).get(key)).filter(Objects::nonNull).toList();
    }

    static Double mean(List<Object> values) {
        List<Double> nums = values.stream().filter(v -> v instanceof Number).map(v -> ((Number) v).doubleValue()).toList();
        return nums.isEmpty() ? null : Math.round(nums.stream().mapToDouble(d -> d).average().orElse(0) * 1000) / 1000.0;
    }

    static Double sum(List<Object> values) {
        return Math.round(values.stream().filter(v -> v instanceof Number)
                .mapToDouble(v -> ((Number) v).doubleValue()).sum() * 10000) / 10000.0;
    }

    static Double rate(List<Object> values) {
        List<Object> bools = values.stream().filter(v -> v instanceof Boolean).toList();
        return bools.isEmpty() ? null
                : Math.round(bools.stream().filter(Boolean.TRUE::equals).count() * 1000.0 / bools.size()) / 1000.0;
    }

    static Double percentile(List<Object> values, double p) {
        List<Double> nums = values.stream().filter(v -> v instanceof Number).map(v -> ((Number) v).doubleValue())
                .sorted().toList();
        if (nums.isEmpty()) {
            return null;
        }
        return nums.get(Math.min(nums.size() - 1, (int) Math.ceil(p * nums.size()) - 1));
    }

    @SuppressWarnings("unchecked")
    private void printConsole(Map<String, Object> summary, Map<String, Object> gates, boolean passed) {
        System.out.println();
        System.out.println("================ EVALUATION SUMMARY ================");
        summary.forEach((k, v) -> {
            if (!(v instanceof Map)) {
                System.out.printf("  %-26s %s%n", k, v);
            }
        });
        System.out.println("---------------- QUALITY GATES ----------------------");
        gates.forEach((k, v) -> {
            Map<String, Object> g = (Map<String, Object>) v;
            System.out.printf("  %-26s %-6s actual=%.3f threshold=%.2f%n", k,
                    Boolean.TRUE.equals(g.get("passed")) ? "PASS" : "FAIL", g.get("actual"), g.get("threshold"));
        });
        System.out.println("  OVERALL: " + (passed ? "PASSED" : "FAILED"));
        System.out.println("  Reports: " + props.outputDir() + "/");
        System.out.println("=====================================================");
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
