package com.aurora.guestops.commons.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** The agents this service hosts, loaded from {@code classpath:agents/catalog.yml}. */
@Component
public class AgentCatalog {

    public static final String LOCATION = "agents/catalog.yml";

    private final Map<String, AgentDefinition> agents = new LinkedHashMap<>();

    public AgentCatalog() {
        ClassPathResource resource = new ClassPathResource(LOCATION);
        if (!resource.exists()) {
            return;
        }
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream in = resource.getInputStream()) {
            CatalogFile file = yaml.readValue(in, CatalogFile.class);
            for (AgentDefinition def : file.agents()) {
                if (agents.put(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate agent id in catalog: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + LOCATION, e);
        }
    }

    public List<AgentDefinition> all() {
        return List.copyOf(agents.values());
    }

    public Optional<AgentDefinition> find(String id) {
        return Optional.ofNullable(agents.get(id));
    }

    public AgentDefinition get(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + id));
    }

    record CatalogFile(List<AgentDefinition> agents) {
    }
}
