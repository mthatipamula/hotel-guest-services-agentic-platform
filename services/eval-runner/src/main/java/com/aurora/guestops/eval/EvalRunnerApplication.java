package com.aurora.guestops.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.aurora.guestops")
@ConfigurationPropertiesScan
public class EvalRunnerApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(EvalRunnerApplication.class, args)));
    }
}
