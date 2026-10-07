package com.aurora.guestops.hoteldata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.aurora.guestops")
@ConfigurationPropertiesScan
public class HotelMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HotelMcpServerApplication.class, args);
    }
}
