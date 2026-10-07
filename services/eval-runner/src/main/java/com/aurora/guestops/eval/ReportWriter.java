package com.aurora.guestops.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Writes report.json, report.html and a Ragas-format JSONL file for the Python Ragas library. */
@Component
public class ReportWriter {

    private final EvalProperties props;
    private final ObjectMapper mapper;

    public ReportWriter(EvalProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @SuppressWarnings("unchecked")
    public void write(Map<String, Object> report, List<Map<String, Object>> results) throws IOException {
        Path dir = Path.of(props.outputDir(), (String) report.get("runId"));
        Files.createDirectories(dir);
        mapper.writeValue(dir.resolve("report.json").toFile(), report);

        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> r : results) {
            if (r.get("groundTruth") == null || r.get("answer") == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_input", r.get("question"));
            row.put("response", r.get("answer"));
            row.put("retrieved_contexts", r.get("contexts"));
            row.put("reference", r.get("groundTruth"));
            jsonl.append(mapper.copy().disable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(row)).append('\n');
        }
        Files.writeString(dir.resolve("ragas-dataset.jsonl"), jsonl, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("report.html"), html(report, results), StandardCharsets.UTF_8);
        Files.writeString(Path.of(props.outputDir(), "latest.txt"), dir.toString(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private String html(Map<String, Object> report, List<Map<String, Object>> results) {
        StringBuilder sb = new StringBuilder("""
                <!doctype html><html><head><meta charset="utf-8"><title>Evaluation report</title>
                <style>body{font:14px system-ui;margin:24px;color:#1d2433}table{border-collapse:collapse;width:100%}
                td,th{border:1px solid #d7dbe3;padding:6px;text-align:left;vertical-align:top}
                .pass{color:#0a7a3d;font-weight:600}.fail{color:#b42318;font-weight:600}</style></head><body>""");
        sb.append("<h1>Evaluation ").append(esc(report.get("runId"))).append(" - ")
                .append(Boolean.TRUE.equals(report.get("passed")) ? "<span class=pass>PASSED</span>" : "<span class=fail>FAILED</span>")
                .append("</h1><p>Judge model: ").append(esc(report.get("judgeModel"))).append("</p><h2>Quality gates</h2><table>")
                .append("<tr><th>Gate</th><th>Actual</th><th>Threshold</th><th>Result</th></tr>");
        ((Map<String, Object>) report.get("gates")).forEach((k, v) -> {
            Map<String, Object> g = (Map<String, Object>) v;
            boolean ok = Boolean.TRUE.equals(g.get("passed"));
            sb.append("<tr><td>").append(esc(k)).append("</td><td>").append(g.get("actual")).append("</td><td>")
                    .append(g.get("threshold")).append("</td><td class=").append(ok ? "pass>PASS" : "fail>FAIL")
                    .append("</td></tr>");
        });
        sb.append("</table><h2>Cases</h2><table><tr><th>Case</th><th>Agent eval</th><th>Ragas</th><th>Judge</th><th>Answer</th></tr>");
        for (Map<String, Object> r : results) {
            sb.append("<tr><td><b>").append(esc(r.get("id"))).append("</b><br>").append(esc(r.get("question")))
                    .append("</td><td>").append(esc(r.get("agent"))).append("</td><td>").append(esc(r.get("ragas")))
                    .append("</td><td>").append(esc(r.get("judge"))).append("</td><td>").append(esc(r.get("answer")))
                    .append("</td></tr>");
        }
        return sb.append("</table></body></html>").toString();
    }

    private static String esc(Object o) {
        return o == null ? "" : HtmlUtils.htmlEscape(String.valueOf(o));
    }
}
