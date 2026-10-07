package com.aurora.guestops.hoteldata;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * RAG over hotel policies and SOPs: ingestion (normalise, chunk by section, embed with Vertex AI,
 * store in pgvector) and the {@code searchHotelPolicies} MCP tool.
 */
@Component
@Order(2)
public class PolicyKnowledge implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicyKnowledge.class);
    private static final Pattern SECTION = Pattern.compile("(?m)^##\\s+(.+)$");
    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s+(.+)$");

    private final VectorStore vectorStore;
    private final JdbcClient jdbc;
    // Token and latency saver: identical policy questions skip the embedding call and vector search.
    private final Cache<String, List<Map<String, Object>>> cache = Caffeine.newBuilder()
            .maximumSize(500).expireAfterWrite(Duration.ofMinutes(15)).build();

    public PolicyKnowledge(VectorStore vectorStore, JdbcClient jdbc) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
    }

    @Tool(description = "Semantic search over hotel policies and SOPs (room moves, service recovery and compensation "
            + "limits, late checkout, housekeeping, maintenance SLAs, billing disputes, loyalty benefits, pets, "
            + "accessibility, dining, groups). Returns the most relevant passages with their source document.")
    public List<Map<String, Object>> searchHotelPolicies(
            @ToolParam(description = "A focused natural-language query, e.g. 'late checkout for GOLD members'") String query,
            @ToolParam(required = false, description = "Number of passages, default 4, max 6") Integer topK) {
        int k = topK == null ? 4 : Math.max(1, Math.min(6, topK));
        String key = query.trim().toLowerCase() + "|" + k;
        return cache.get(key, q -> vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query).topK(k).similarityThreshold(0.35).build())
                .stream().map(PolicyKnowledge::toResult).toList());
    }

    private static Map<String, Object> toResult(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", d.getMetadata().get("source"));
        m.put("title", d.getMetadata().get("section"));
        m.put("score", d.getScore() == null ? null : Math.round(d.getScore() * 1000) / 1000.0);
        m.put("text", d.getText());
        return m;
    }

    /** Ingest on startup when the store is empty or the documents changed (checksum stored per chunk). */
    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<Document> docs = loadAndChunk();
        String version = Integer.toHexString(docs.stream().map(Document::getText).toList().hashCode());
        Integer existing = jdbc.sql("SELECT count(*) FROM policy_chunks WHERE metadata->>'corpusVersion' = :v")
                .param("v", version).query(Integer.class).single();
        if (existing != null && existing == docs.size()) {
            log.info("Policy corpus {} already indexed ({} chunks)", version, existing);
            return;
        }
        jdbc.sql("DELETE FROM policy_chunks").update();
        docs.forEach(d -> d.getMetadata().put("corpusVersion", version));
        vectorStore.add(docs);
        cache.invalidateAll();
        log.info("Indexed {} policy chunks (corpus {}) with Vertex AI embeddings", docs.size(), version);
    }

    /** Normalise and chunk: one chunk per "##" section, prefixed with the document title for context. */
    static List<Document> chunk(String source, String markdown) {
        String text = markdown.replace("\r\n", "\n").replaceAll("[ \\t]+\n", "\n").trim();
        Matcher t = TITLE.matcher(text);
        String title = t.find() ? t.group(1).trim() : source;
        List<Document> out = new ArrayList<>();
        Matcher m = SECTION.matcher(text);
        List<int[]> starts = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[] {m.start(), m.end()});
            headings.add(m.group(1).trim());
        }
        for (int i = 0; i < starts.size(); i++) {
            int bodyStart = starts.get(i)[1];
            int bodyEnd = i + 1 < starts.size() ? starts.get(i + 1)[0] : text.length();
            String body = text.substring(bodyStart, bodyEnd).trim().replaceAll("\\s+", " ");
            if (body.isEmpty()) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", source);
            meta.put("document", title);
            meta.put("section", headings.get(i));
            out.add(new Document(title + " - " + headings.get(i) + ": " + body, meta));
        }
        return out;
    }

    private static List<Document> loadAndChunk() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:policies/*.md");
        List<Document> docs = new ArrayList<>();
        for (Resource r : files) {
            try {
                docs.addAll(chunk(r.getFilename(), r.getContentAsString(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        docs.sort(java.util.Comparator.comparing(d -> d.getMetadata().get("source") + "/" + d.getMetadata().get("section")));
        return docs;
    }
}
