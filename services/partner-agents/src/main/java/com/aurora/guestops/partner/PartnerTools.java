package com.aurora.guestops.partner;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** Simulated partner APIs (fleet, experiences catalogue, spa booking system). */
@Component
public class PartnerTools {

    @Tool(description = "Quote ground transport. destination examples: ORD airport, MDW airport, downtown address. "
            + "pickupTime as yyyy-MM-dd'T'HH:mm.")
    public Map<String, Object> quoteGroundTransport(
            @ToolParam(description = "Pickup time, e.g. 2026-10-08T06:00") String pickupTime,
            @ToolParam(description = "Destination") String destination,
            @ToolParam(description = "Passengers") Integer passengers) {
        int pax = passengers == null ? 1 : passengers;
        boolean ord = destination != null && destination.toUpperCase(Locale.ROOT).contains("ORD");
        boolean mdw = destination != null && destination.toUpperCase(Locale.ROOT).contains("MDW");
        int base = ord ? 95 : mdw ? 75 : 45;
        List<Map<String, Object>> options = new ArrayList<>();
        if (pax <= 3) {
            options.add(option("SEDAN", base, ord ? 45 : 35));
        }
        if (pax <= 6) {
            options.add(option("SUV", base + 30, ord ? 45 : 35));
        }
        options.add(option("VAN", base + 60, ord ? 50 : 40));
        return Map.of("pickupTime", pickupTime, "destination", String.valueOf(destination), "passengers", pax,
                "options", options, "note", "Prices include tolls; gratuity not included.");
    }

    @Tool(description = "Place a 30-minute hold on a quoted vehicle type. Not a booking until staff confirm.")
    public Map<String, Object> holdGroundTransport(
            @ToolParam(description = "Vehicle type from the quote: SEDAN, SUV or VAN") String vehicleType,
            @ToolParam(description = "Pickup time, e.g. 2026-10-08T06:00") String pickupTime) {
        return Map.of("holdId", "TRN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "vehicleType", String.valueOf(vehicleType), "pickupTime", String.valueOf(pickupTime),
                "status", "HELD", "expiresAt", LocalDateTime.now().plusMinutes(30)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    @Tool(description = "Search the partner catalogue of local experiences. category: dining, tours, shows, family.")
    public List<Map<String, Object>> searchLocalExperiences(
            @ToolParam(description = "Category: dining, tours, shows or family") String category,
            @ToolParam(required = false, description = "Party size") Integer partySize,
            @ToolParam(required = false, description = "Date, yyyy-MM-dd") String date) {
        String c = category == null ? "" : category.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> all = List.of(
                exp("dining", "Alinea-style tasting menu at Ember & Oak", "19:30 or 21:00", "$185 pp", "0.8 mi", "Romantic, quiet"),
                exp("dining", "Riverside Italian Trattoria", "18:00-22:00", "$65 pp", "0.4 mi", "Anniversary dessert on request"),
                exp("dining", "Deep-dish pizza kitchen", "11:00-23:00", "$25 pp", "0.3 mi", "Family friendly"),
                exp("tours", "Chicago architecture river cruise", "10:00, 13:00, 16:00", "$52 pp", "0.5 mi", "90 minutes"),
                exp("tours", "Sunset lakefront bike tour", "17:30", "$45 pp", "1.1 mi", "Bikes included"),
                exp("shows", "Second City improv", "20:00", "$49 pp", "1.6 mi", "Ages 16+"),
                exp("shows", "Jazz at the Green Mill", "21:00", "$20 cover", "6.0 mi", "Historic venue"),
                exp("family", "Field Museum fast-track entry", "09:00-17:00", "$40 adult, $30 child", "1.9 mi", "Dinosaur hall"));
        return all.stream().filter(e -> c.isBlank() || e.get("category").equals(c)).toList();
    }

    @Tool(description = "Spa availability for a date. treatmentType: massage, facial, couples.")
    public List<Map<String, Object>> getSpaAvailability(
            @ToolParam(description = "Date, yyyy-MM-dd") String date,
            @ToolParam(description = "Treatment type: massage, facial or couples") String treatmentType) {
        LocalDate d = LocalDate.parse(date.trim());
        String t = treatmentType == null ? "massage" : treatmentType.toLowerCase(Locale.ROOT);
        int price = switch (t) {
            case "couples" -> 340;
            case "facial" -> 155;
            default -> 165;
        };
        List<Map<String, Object>> slots = new ArrayList<>();
        for (String time : List.of("10:00", "13:30", "15:00", "17:30")) {
            // Deterministic availability per date and time.
            if ((d.getDayOfYear() + time.hashCode()) % 3 != 0) {
                slots.add(Map.of("slotId", "SPA-" + d + "-" + time.replace(":", ""), "date", d.toString(),
                        "time", time, "treatment", t, "durationMinutes", t.equals("couples") ? 80 : 60,
                        "price", price));
            }
        }
        return slots;
    }

    @Tool(description = "Hold a spa slot for 30 minutes. Not a booking until staff confirm with the guest.")
    public Map<String, Object> holdSpaAppointment(@ToolParam(description = "slotId from getSpaAvailability") String slotId) {
        return Map.of("holdId", "SPAH-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "slotId", String.valueOf(slotId), "status", "HELD",
                "expiresAt", LocalDateTime.now().plusMinutes(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    private static Map<String, Object> option(String vehicle, int price, int minutes) {
        return Map.of("vehicleType", vehicle, "priceUsd", price, "estimatedMinutes", minutes);
    }

    private static Map<String, Object> exp(String category, String name, String times, String price, String distance,
                                           String notes) {
        return Map.of("category", category, "name", name, "times", times, "price", price, "distance", distance,
                "notes", notes);
    }

    @Configuration
    static class Registration {
        @Bean
        ToolCallbackProvider partnerToolProvider(PartnerTools tools) {
            return MethodToolCallbackProvider.builder().toolObjects(tools).build();
        }
    }
}
