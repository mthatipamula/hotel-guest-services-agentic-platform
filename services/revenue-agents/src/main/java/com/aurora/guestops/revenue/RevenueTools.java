package com.aurora.guestops.revenue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Revenue management tools. The forecast is a deterministic model (day of week + city events) so the
 * demo is repeatable; in production these would call the revenue management system's API.
 */
@Component
public class RevenueTools {

    static final int PROPERTY_ROOMS = 412;
    private static final Map<String, BigDecimal> BASE_RATES = Map.of(
            "KING", new BigDecimal("269"), "QQ", new BigDecimal("249"),
            "JRS", new BigDecimal("449"), "EXS", new BigDecimal("799"));
    private static final Map<String, String> NEXT_TYPE = Map.of("QQ", "JRS", "KING", "JRS", "JRS", "EXS");

    @Tool(description = "Occupancy forecast per night (property has 412 rooms) with demand drivers.")
    public List<Map<String, Object>> getOccupancyForecast(
            @ToolParam(description = "Start date, yyyy-MM-dd") String startDate,
            @ToolParam(required = false, description = "Number of nights, default 7, max 31") Integer days) {
        LocalDate start = LocalDate.parse(startDate.trim());
        int n = days == null ? 7 : Math.max(1, Math.min(31, days));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate d = start.plusDays(i);
            double occ = occupancy(d);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("dayOfWeek", d.getDayOfWeek().toString());
            row.put("forecastOccupancyPct", Math.round(occ * 1000) / 10.0);
            row.put("roomsAvailable", (int) Math.round(PROPERTY_ROOMS * (1 - occ)));
            row.put("drivers", drivers(d));
            out.add(row);
        }
        return out;
    }

    @Tool(description = "Dynamic rate quote for a room type (KING, QQ, JRS, EXS), check-in date and nights. "
            + "Prices are per night before 17.4% tax.")
    public Map<String, Object> getRateQuote(
            @ToolParam(description = "Room type code") String roomType,
            @ToolParam(description = "Check-in date, yyyy-MM-dd") String checkInDate,
            @ToolParam(description = "Number of nights") Integer nights) {
        String type = roomType == null ? "" : roomType.trim().toUpperCase();
        BigDecimal base = BASE_RATES.get(type);
        if (base == null) {
            return Map.of("error", "unknown room type " + roomType + "; use KING, QQ, JRS or EXS");
        }
        LocalDate start = LocalDate.parse(checkInDate.trim());
        int n = nights == null || nights < 1 ? 1 : Math.min(nights, 30);
        List<Map<String, Object>> perNight = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            LocalDate d = start.plusDays(i);
            BigDecimal rate = dynamicRate(base, occupancy(d));
            total = total.add(rate);
            perNight.add(Map.of("date", d.toString(), "rate", rate,
                    "forecastOccupancyPct", Math.round(occupancy(d) * 1000) / 10.0));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomType", type);
        out.put("nights", perNight);
        out.put("totalBeforeTax", total);
        out.put("currency", "USD");
        return out;
    }

    @Tool(description = "Upgrade offers from the current room type for a loyalty tier: complimentary when it is a "
            + "tier benefit, otherwise a paid upgrade price per night.")
    public Map<String, Object> getUpgradeOffers(
            @ToolParam(description = "Current room type code") String currentRoomType,
            @ToolParam(description = "Loyalty tier: MEMBER, SILVER, GOLD or PLATINUM") String loyaltyTier,
            @ToolParam(description = "Check-in date, yyyy-MM-dd") String checkInDate) {
        String from = currentRoomType == null ? "" : currentRoomType.trim().toUpperCase();
        String to = NEXT_TYPE.get(from);
        if (to == null) {
            return Map.of("offers", List.of(), "note", "no higher room type than " + from);
        }
        String tier = loyaltyTier == null ? "MEMBER" : loyaltyTier.trim().toUpperCase();
        double occ = occupancy(LocalDate.parse(checkInDate.trim()));
        BigDecimal diff = dynamicRate(BASE_RATES.get(to), occ).subtract(dynamicRate(BASE_RATES.get(from), occ));
        BigDecimal discount = switch (tier) {
            case "GOLD" -> new BigDecimal("0.20");
            case "SILVER" -> new BigDecimal("0.10");
            default -> BigDecimal.ZERO;
        };
        boolean complimentary = "PLATINUM".equals(tier) && "JRS".equals(to);
        BigDecimal price = complimentary ? BigDecimal.ZERO
                : diff.multiply(BigDecimal.ONE.subtract(discount)).setScale(0, RoundingMode.HALF_UP);
        Map<String, Object> offer = new LinkedHashMap<>();
        offer.put("toRoomType", to);
        offer.put("complimentary", complimentary);
        offer.put("pricePerNight", price);
        offer.put("basis", complimentary ? "PLATINUM benefit: Junior Suite upgrade when available"
                : "tier discount " + discount.movePointRight(2).intValue() + "% on the rate difference");
        return Map.of("fromRoomType", from, "loyaltyTier", tier, "offers", List.of(offer));
    }

    @Tool(description = "Check whether a group block fits: rooms requested per night versus forecast availability, "
            + "with the group rate range from the revenue strategy.")
    public Map<String, Object> checkGroupAvailability(
            @ToolParam(description = "Arrival date, yyyy-MM-dd") String arrivalDate,
            @ToolParam(description = "Rooms per night") Integer rooms,
            @ToolParam(description = "Number of nights") Integer nights) {
        LocalDate start = LocalDate.parse(arrivalDate.trim());
        int n = nights == null || nights < 1 ? 1 : Math.min(nights, 14);
        int requested = rooms == null ? 0 : rooms;
        List<Map<String, Object>> perNight = new ArrayList<>();
        boolean fits = true;
        double maxOcc = 0;
        for (int i = 0; i < n; i++) {
            LocalDate d = start.plusDays(i);
            double occ = occupancy(d);
            maxOcc = Math.max(maxOcc, occ);
            int available = (int) Math.round(PROPERTY_ROOMS * (1 - occ));
            // Revenue strategy: groups may take at most 60% of the remaining inventory on a night.
            int groupCap = (int) Math.floor(available * 0.6);
            fits &= requested <= groupCap;
            perNight.add(Map.of("date", d.toString(), "roomsAvailable", available, "groupCap", groupCap));
        }
        String discount = maxOcc > 0.85 ? "0-5%" : maxOcc > 0.70 ? "10-15%" : "15-20%";
        return Map.of("requestedRoomsPerNight", requested, "nights", perNight, "fits", fits,
                "recommendedDiscountOffBAR", discount);
    }

    static double occupancy(LocalDate d) {
        double occ = switch (d.getDayOfWeek()) {
            case FRIDAY, SATURDAY -> 0.86;
            case THURSDAY -> 0.80;
            case SUNDAY -> 0.58;
            default -> 0.72;
        };
        if (!drivers(d).isEmpty()) {
            occ += 0.08;
        }
        // Small deterministic variation so neighbouring weeks differ.
        occ += ((d.getDayOfYear() * 37) % 7 - 3) / 100.0;
        return Math.max(0.35, Math.min(0.98, occ));
    }

    static List<String> drivers(LocalDate d) {
        List<String> out = new ArrayList<>();
        if (d.getMonthValue() == 6 && d.getDayOfWeek() == DayOfWeek.SATURDAY) {
            out.add("June wedding season");
        }
        if (d.getDayOfMonth() >= 10 && d.getDayOfMonth() <= 13) {
            out.add("Citywide convention at McCormick Place");
        }
        if (d.getDayOfWeek() == DayOfWeek.FRIDAY || d.getDayOfWeek() == DayOfWeek.SATURDAY) {
            out.add("Weekend leisure demand");
        }
        return out;
    }

    static BigDecimal dynamicRate(BigDecimal base, double occupancy) {
        double factor = 1 + (occupancy - 0.70) * 0.9;
        factor = Math.max(0.85, Math.min(1.35, factor));
        return base.multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP);
    }

    @Configuration
    static class Registration {
        @Bean
        ToolCallbackProvider revenueToolProvider(RevenueTools tools) {
            return MethodToolCallbackProvider.builder().toolObjects(tools).build();
        }
    }
}
