package com.aurora.guestops.hoteldata;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hotel operations tools served over MCP. Read tools return only what agents need (contact details
 * are masked: data minimisation). Write tools validate every change in code. The three high-impact
 * tools (room move, folio credit, late checkout) are excluded from every agent's governance
 * allowlist; only the human-approval workflow calls them.
 */
@Component
public class HotelOperationsTools {

    private static final Set<String> HK_TYPES = Set.of("DEPARTURE_CLEAN", "STAYOVER", "RUSH_CLEAN", "TURNDOWN", "AMENITY");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> CATEGORIES = Set.of("HVAC", "PLUMBING", "ELECTRICAL", "NOISE", "FURNITURE", "OTHER");
    private static final Map<String, LocalTime> MAX_LATE_CHECKOUT = Map.of(
            "MEMBER", LocalTime.of(13, 0), "SILVER", LocalTime.of(14, 0),
            "GOLD", LocalTime.of(14, 0), "PLATINUM", LocalTime.of(16, 0));

    private final HotelRepository repo;
    private final HotelProperties props;

    public HotelOperationsTools(HotelRepository repo, HotelProperties props) {
        this.repo = repo;
        this.props = props;
    }

    // ---------------- Read tools ----------------

    @Tool(description = "Look up a reservation by confirmation number (e.g. AUR-10021): guest, loyalty tier, room, "
            + "dates, rate plan, status and special requests.")
    public Map<String, Object> getReservation(
            @ToolParam(description = "Confirmation number, e.g. AUR-10021") String confirmationNumber) {
        return repo.reservation(confirmationNumber)
                .<Map<String, Object>>map(LinkedHashMap::new)
                .orElse(Map.of("error", "No reservation found for " + confirmationNumber));
    }

    @Tool(description = "Find reservations by guest last name. Use when the confirmation number is not known.")
    public List<Map<String, Object>> findReservationsByGuestName(
            @ToolParam(description = "Guest last name") String lastName) {
        return repo.reservationsByLastName(lastName);
    }

    @Tool(description = "List in-house and arriving reservations (staff console helper).")
    public List<Map<String, Object>> listActiveReservations() {
        return repo.activeReservations();
    }

    @Tool(description = "Guest profile: loyalty tier, lifetime nights, preferences and service notes. "
            + "Contact details are masked.")
    public Map<String, Object> getGuestProfile(@ToolParam(description = "Guest id, e.g. G-1001") String guestId) {
        return repo.guest(guestId).map(g -> {
            Map<String, Object> out = new LinkedHashMap<>(g);
            out.put("email", maskEmail((String) g.get("email")));
            out.put("phone", maskPhone((String) g.get("phone")));
            out.put("loyalty_number", maskTail((String) g.get("loyalty_number")));
            return out;
        }).orElse(Map.of("error", "No guest " + guestId));
    }

    @Tool(description = "Current status of one room: type, floor, view, features, accessibility and housekeeping status "
            + "(VACANT_CLEAN, VACANT_DIRTY, OCCUPIED, OUT_OF_ORDER).")
    public Map<String, Object> getRoomStatus(@ToolParam(description = "Room number, e.g. 1208") String roomNumber) {
        return repo.room(roomNumber).<Map<String, Object>>map(LinkedHashMap::new)
                .orElse(Map.of("error", "No room " + roomNumber));
    }

    @Tool(description = "Rooms that can be assigned right now: VACANT_CLEAN, no open maintenance ticket, not held for "
            + "an active booking. Filter by room type (KING, QQ, JRS, EXS), minimum floor and accessibility.")
    public List<Map<String, Object>> findAvailableRooms(
            @ToolParam(required = false, description = "Room type code: KING, QQ, JRS or EXS. Omit for any.") String roomType,
            @ToolParam(required = false, description = "Minimum floor") Integer minFloor,
            @ToolParam(required = false, description = "true for accessible rooms only") Boolean accessibleOnly) {
        return repo.availableRooms(roomType, minFloor, accessibleOnly);
    }

    @Tool(description = "Housekeeping tasks for a room, newest first, with status, attendant and ETA in minutes.")
    public List<Map<String, Object>> getHousekeepingStatus(@ToolParam(description = "Room number") String roomNumber) {
        return repo.housekeeping(roomNumber);
    }

    @Tool(description = "Maintenance tickets for a room, newest first.")
    public List<Map<String, Object>> getMaintenanceTickets(@ToolParam(description = "Room number") String roomNumber) {
        return repo.maintenance(roomNumber);
    }

    @Tool(description = "Folio (bill) for a reservation: every charge and credit with date, category and amount, plus the balance.")
    public Map<String, Object> getFolio(@ToolParam(description = "Confirmation number") String confirmationNumber) {
        List<Map<String, Object>> lines = repo.folio(confirmationNumber);
        BigDecimal balance = lines.stream().map(l -> (BigDecimal) l.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("confirmation_number", confirmationNumber);
        out.put("charges", lines);
        out.put("balance", balance);
        return out;
    }

    @Tool(description = "Dining outlets with hours: Lakeside Grill, Ember Lounge, in-room dining and the Club Lounge.")
    public List<Map<String, Object>> getDiningOutlets() {
        return List.of(
                Map.of("name", "Lakeside Grill", "level", 2, "hours", "Breakfast 6:30-10:30, dinner 17:30-22:00",
                        "notes", "Reservations recommended Thu-Sat"),
                Map.of("name", "Ember Lounge", "level", "Lobby", "hours", "16:00-24:00",
                        "notes", "Happy hour 16:00-18:00"),
                Map.of("name", "In-room dining", "hours", "06:00-23:00",
                        "notes", "35 minute delivery target, $5 delivery fee, 18% service charge"),
                Map.of("name", "Club Lounge", "level", 14, "hours", "Breakfast 6:30-10:00, canapes 17:00-19:00",
                        "notes", "PLATINUM members and Executive Suite guests"));
    }

    // ---------------- Low-risk write tools (agents may call directly) ----------------

    @Tool(description = "Request a housekeeping task. taskType: DEPARTURE_CLEAN, STAYOVER, RUSH_CLEAN, TURNDOWN, AMENITY. "
            + "priority: LOW, NORMAL, HIGH, URGENT.")
    @Transactional
    public Map<String, Object> requestHousekeeping(
            @ToolParam(description = "Room number") String roomNumber,
            @ToolParam(description = "Task type") String taskType,
            @ToolParam(description = "Priority") String priority,
            @ToolParam(required = false, description = "Notes for the attendant") String notes) {
        String type = upper(taskType);
        String prio = upper(priority);
        if (!HK_TYPES.contains(type) || !PRIORITIES.contains(prio)) {
            return Map.of("status", "rejected", "reason", "invalid taskType or priority");
        }
        if (repo.room(roomNumber).isEmpty()) {
            return Map.of("status", "rejected", "reason", "unknown room " + roomNumber);
        }
        int eta = switch (type) {
            case "RUSH_CLEAN" -> 20;
            case "DEPARTURE_CLEAN" -> 45;
            case "STAYOVER" -> 25;
            default -> 10;
        };
        long id = repo.createHousekeepingTask(roomNumber, type, prio, eta, notes);
        repo.log("requestHousekeeping", roomNumber, type + " " + prio, "agent");
        return Map.of("status", "created", "taskId", id, "room", roomNumber, "taskType", type, "etaMinutes", eta);
    }

    @Tool(description = "Create a maintenance ticket. category: HVAC, PLUMBING, ELECTRICAL, NOISE, FURNITURE, OTHER. "
            + "priority: LOW, NORMAL, HIGH, URGENT (URGENT = room unusable or safety risk while occupied).")
    @Transactional
    public Map<String, Object> createMaintenanceTicket(
            @ToolParam(description = "Room number") String roomNumber,
            @ToolParam(description = "Category") String category,
            @ToolParam(description = "Short description of the issue") String issue,
            @ToolParam(description = "Priority") String priority) {
        String cat = upper(category);
        String prio = upper(priority);
        if (!CATEGORIES.contains(cat) || !PRIORITIES.contains(prio)) {
            return Map.of("status", "rejected", "reason", "invalid category or priority");
        }
        if (repo.room(roomNumber).isEmpty()) {
            return Map.of("status", "rejected", "reason", "unknown room " + roomNumber);
        }
        long id = repo.createMaintenanceTicket(roomNumber, cat, issue, prio);
        repo.log("createMaintenanceTicket", roomNumber, cat + " " + prio + ": " + issue, "agent");
        String sla = switch (prio) {
            case "URGENT" -> "engineer on site within 15 minutes";
            case "HIGH" -> "response within 30 minutes";
            case "NORMAL" -> "response within 2 hours";
            default -> "scheduled maintenance";
        };
        return Map.of("status", "created", "ticketId", id, "room", roomNumber, "priority", prio, "sla", sla);
    }

    // ---------------- High-impact tools (human approval workflow only) ----------------

    @Tool(description = "HIGH IMPACT - only after manager approval. Move a reservation to another room.")
    @Transactional
    public Map<String, Object> moveGuestToRoom(
            @ToolParam(description = "Confirmation number") String confirmationNumber,
            @ToolParam(description = "New room number") String newRoomNumber,
            @ToolParam(description = "Reason for the move") String reason,
            @ToolParam(description = "Approving manager") String approvedBy) {
        Map<String, Object> res = repo.reservation(confirmationNumber).orElse(null);
        if (res == null) {
            return Map.of("status", "rejected", "reason", "unknown reservation");
        }
        Map<String, Object> room = repo.room(newRoomNumber).orElse(null);
        if (room == null || !"VACANT_CLEAN".equals(room.get("status"))) {
            return Map.of("status", "rejected", "reason", "room " + newRoomNumber + " is not VACANT_CLEAN");
        }
        if (!repo.maintenance(newRoomNumber).stream().allMatch(t -> "RESOLVED".equals(t.get("status")))) {
            return Map.of("status", "rejected", "reason", "room " + newRoomNumber + " has an open maintenance ticket");
        }
        String from = (String) res.get("room_number");
        repo.moveReservation(confirmationNumber, from, newRoomNumber);
        if (from != null) {
            repo.createHousekeepingTask(from, "DEPARTURE_CLEAN", "HIGH", 45, "Vacated by room move " + confirmationNumber);
        }
        repo.log("moveGuestToRoom", confirmationNumber, from + " -> " + newRoomNumber + ": " + reason, approvedBy);
        return Map.of("status", "executed", "confirmationNumber", confirmationNumber, "fromRoom", String.valueOf(from),
                "toRoom", newRoomNumber, "approvedBy", approvedBy);
    }

    @Tool(description = "HIGH IMPACT - only after manager approval. Post a service-recovery credit to the folio. "
            + "Enforces the per-stay credit limit for the guest's loyalty tier.")
    @Transactional
    public Map<String, Object> applyFolioCredit(
            @ToolParam(description = "Confirmation number") String confirmationNumber,
            @ToolParam(description = "Credit amount in USD, positive number") BigDecimal amount,
            @ToolParam(description = "Reason") String reason,
            @ToolParam(description = "Approving manager") String approvedBy) {
        Map<String, Object> res = repo.reservation(confirmationNumber).orElse(null);
        if (res == null) {
            return Map.of("status", "rejected", "reason", "unknown reservation");
        }
        if (amount == null || amount.signum() <= 0) {
            return Map.of("status", "rejected", "reason", "amount must be positive");
        }
        String tier = (String) res.get("loyalty_tier");
        BigDecimal limit = props.creditLimit(tier);
        BigDecimal already = repo.creditsThisStay(confirmationNumber);
        if (already.add(amount).compareTo(limit) > 0) {
            return Map.of("status", "rejected", "reason", "exceeds " + tier + " per-stay credit limit of $" + limit
                    + " (already credited $" + already + "); General Manager approval required");
        }
        repo.postCredit(confirmationNumber, amount, reason, approvedBy);
        repo.log("applyFolioCredit", confirmationNumber, amount + " " + reason, approvedBy);
        return Map.of("status", "executed", "confirmationNumber", confirmationNumber, "credit", amount,
                "approvedBy", approvedBy);
    }

    @Tool(description = "HIGH IMPACT - only after manager approval. Set a late checkout time (HH:mm) within the "
            + "guest's loyalty-tier entitlement.")
    @Transactional
    public Map<String, Object> grantLateCheckout(
            @ToolParam(description = "Confirmation number") String confirmationNumber,
            @ToolParam(description = "Checkout time, 24h HH:mm, e.g. 14:00") String checkoutTime,
            @ToolParam(description = "Approving manager") String approvedBy) {
        Map<String, Object> res = repo.reservation(confirmationNumber).orElse(null);
        if (res == null) {
            return Map.of("status", "rejected", "reason", "unknown reservation");
        }
        LocalTime requested;
        try {
            requested = LocalTime.parse(checkoutTime.trim());
        } catch (RuntimeException e) {
            return Map.of("status", "rejected", "reason", "time must be HH:mm");
        }
        String tier = (String) res.get("loyalty_tier");
        LocalTime max = MAX_LATE_CHECKOUT.getOrDefault(tier, LocalTime.NOON);
        if (requested.isAfter(max)) {
            return Map.of("status", "rejected", "reason", tier + " entitlement is up to " + max
                    + "; later checkout is chargeable and needs front office manager handling");
        }
        repo.setCheckoutTime(confirmationNumber, requested.toString());
        repo.log("grantLateCheckout", confirmationNumber, requested.toString(), approvedBy);
        return Map.of("status", "executed", "confirmationNumber", confirmationNumber,
                "checkoutTime", requested.toString(), "approvedBy", approvedBy);
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }

    static String maskPhone(String phone) {
        return phone == null || phone.length() < 4 ? null : "***-***-" + phone.substring(phone.length() - 4);
    }

    static String maskTail(String value) {
        return value == null || value.length() < 4 ? null : "****" + value.substring(value.length() - 4);
    }
}
