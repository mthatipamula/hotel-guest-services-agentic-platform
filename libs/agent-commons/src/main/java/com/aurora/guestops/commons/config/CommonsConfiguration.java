package com.aurora.guestops.commons.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(GuestOpsProperties.class)
public class CommonsConfiguration {

    /** Virtual threads: agent calls are I/O bound (LLM, A2A, MCP), so one thread per call is cheap. */
    @Bean(destroyMethod = "close")
    ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
