package com.aurora.guestops.partner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.aurora.guestops")
public class PartnerAgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PartnerAgentsApplication.class, args);
    }
}
