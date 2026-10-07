package com.aurora.guestops.hoteldata;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "guestops.hotel")
public record HotelProperties(Map<String, BigDecimal> creditLimits,
                              @DefaultValue("true") boolean resetDemoData) {

    public BigDecimal creditLimit(String tier) {
        return creditLimits == null ? BigDecimal.ZERO : creditLimits.getOrDefault(tier, BigDecimal.ZERO);
    }
}
