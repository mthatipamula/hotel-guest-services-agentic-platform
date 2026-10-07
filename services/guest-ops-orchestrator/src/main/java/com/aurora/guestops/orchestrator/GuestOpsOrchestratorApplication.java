package com.aurora.guestops.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.aurora.guestops")
@ConfigurationPropertiesScan
public class GuestOpsOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuestOpsOrchestratorApplication.class, args);
    }
}
