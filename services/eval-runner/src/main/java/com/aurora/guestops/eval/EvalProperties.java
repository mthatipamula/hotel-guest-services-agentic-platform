package com.aurora.guestops.eval;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "guestops.eval")
public record EvalProperties(
        @DefaultValue("http://localhost:8080") String orchestratorUrl,
        @DefaultValue("classpath:golden-dataset.json") String dataset,
        @DefaultValue("eval-reports") String outputDir,
        @DefaultValue("3") int concurrency,
        @DefaultValue("true") boolean publish,
        @DefaultValue("false") boolean failOnThreshold,
        Map<String, Double> thresholds) {
}
