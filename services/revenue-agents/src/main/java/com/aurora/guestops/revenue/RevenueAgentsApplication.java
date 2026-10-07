package com.aurora.guestops.revenue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.aurora.guestops")
public class RevenueAgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(RevenueAgentsApplication.class, args);
    }
}
