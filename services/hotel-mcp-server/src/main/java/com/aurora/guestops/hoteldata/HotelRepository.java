package com.aurora.guestops.hoteldata;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HotelRepository {

    private final JdbcClient jdbc;

    public HotelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> reservation(String confirmationNumber) {
        return jdbc.sql("""
                SELECT r.confirmation_number, r.status, r.check_in, r.check_out, r.checkout_time::text AS checkout_time,
                       r.room_number, r.room_type, r.nightly_rate, r.rate_plan, r.adults, r.special_requests, r.eta,
                       g.guest_id, g.first_name || ' ' || g.last_name AS guest_name, g.loyalty_tier,
                       rm.type_name AS room_type_name, rm.floor, rm.status AS room_status
                FROM reservations r JOIN guests g ON g.guest_id = r.guest_id
                LEFT JOIN rooms rm ON rm.room_number = r.room_number
                WHERE upper(r.confirmation_number) = upper(:c)""")
                .param("c", confirmationNumber.trim()).query().listOfRows().stream().findFirst();
    }

    public List<Map<String, Object>> reservationsByLastName(String lastName) {
        return jdbc.sql("""
                SELECT r.confirmation_number, r.status, r.check_in, r.check_out, r.room_number,
                       g.first_name || ' ' || g.last_name AS guest_name, g.loyalty_tier
                FROM reservations r JOIN guests g ON g.guest_id = r.guest_id
                WHERE lower(g.last_name) = lower(:n) ORDER BY r.check_in""")
                .param("n", lastName.trim()).query().listOfRows();
    }

    public List<Map<String, Object>> activeReservations() {
        return jdbc.sql("""
                SELECT r.confirmation_number, r.status, r.check_in, r.check_out, r.room_number, r.room_type,
                       g.first_name || ' ' || g.last_name AS guest_name, g.loyalty_tier
                FROM reservations r JOIN guests g ON g.guest_id = r.guest_id
                WHERE r.status IN ('IN_HOUSE', 'RESERVED') ORDER BY r.status, r.confirmation_number""")
                .query().listOfRows();
    }

    public Optional<Map<String, Object>> guest(String guestId) {
        return jdbc.sql("""
                SELECT guest_id, first_name, last_name, loyalty_tier, lifetime_nights, preferences, notes,
                       email, phone, loyalty_number
                FROM guests WHERE upper(guest_id) = upper(:id)""")
                .param("id", guestId.trim()).query().listOfRows().stream().findFirst();
    }

    public Optional<Map<String, Object>> room(String roomNumber) {
        return jdbc.sql("SELECT * FROM rooms WHERE room_number = :r")
                .param("r", roomNumber.trim()).query().listOfRows().stream().findFirst();
    }

    /** Vacant clean rooms with no open maintenance ticket and not already assigned to an active booking. */
    public List<Map<String, Object>> availableRooms(String roomType, Integer minFloor, Boolean accessibleOnly) {
        return jdbc.sql("""
                SELECT rm.room_number, rm.floor, rm.room_type, rm.type_name, rm.accessible, rm.view, rm.features
                FROM rooms rm
                WHERE rm.status = 'VACANT_CLEAN'
                  AND (CAST(:type AS varchar) IS NULL OR rm.room_type = :type)
                  AND (CAST(:minFloor AS integer) IS NULL OR rm.floor >= :minFloor)
                  AND (CAST(:accessible AS boolean) IS NULL OR rm.accessible = :accessible)
                  AND NOT EXISTS (SELECT 1 FROM maintenance_tickets t
                                  WHERE t.room_number = rm.room_number AND t.status <> 'RESOLVED')
                  AND NOT EXISTS (SELECT 1 FROM reservations r
                                  WHERE r.room_number = rm.room_number AND r.status IN ('IN_HOUSE', 'RESERVED')
                                    AND r.check_in <= CURRENT_DATE)
                ORDER BY rm.floor DESC, rm.room_number""")
                .param("type", blankToNull(roomType == null ? null : roomType.toUpperCase()))
                .param("minFloor", minFloor)
                .param("accessible", accessibleOnly)
                .query().listOfRows();
    }

    public List<Map<String, Object>> housekeeping(String roomNumber) {
        return jdbc.sql("""
                SELECT task_id, room_number, task_type, status, priority, assigned_to, eta_minutes, notes, created_at
                FROM housekeeping_tasks WHERE room_number = :r ORDER BY created_at DESC""")
                .param("r", roomNumber.trim()).query().listOfRows();
    }

    public long createHousekeepingTask(String room, String type, String priority, Integer eta, String notes) {
        return jdbc.sql("""
                INSERT INTO housekeeping_tasks (room_number, task_type, status, priority, eta_minutes, notes)
                VALUES (:r, :t, 'QUEUED', :p, :eta, :n) RETURNING task_id""")
                .param("r", room).param("t", type).param("p", priority).param("eta", eta).param("n", notes)
                .query(Long.class).single();
    }

    public List<Map<String, Object>> maintenance(String roomNumber) {
        return jdbc.sql("""
                SELECT ticket_id, room_number, category, issue, priority, status, assigned_to, created_at
                FROM maintenance_tickets WHERE room_number = :r ORDER BY created_at DESC""")
                .param("r", roomNumber.trim()).query().listOfRows();
    }

    public long createMaintenanceTicket(String room, String category, String issue, String priority) {
        return jdbc.sql("""
                INSERT INTO maintenance_tickets (room_number, category, issue, priority, status)
                VALUES (:r, :c, :i, :p, 'OPEN') RETURNING ticket_id""")
                .param("r", room).param("c", category).param("i", issue).param("p", priority)
                .query(Long.class).single();
    }

    public List<Map<String, Object>> folio(String confirmationNumber) {
        return jdbc.sql("""
                SELECT charge_id, charge_date, category, description, amount, posted_by
                FROM folio_charges WHERE upper(confirmation_number) = upper(:c) ORDER BY charge_date, charge_id""")
                .param("c", confirmationNumber.trim()).query().listOfRows();
    }

    public BigDecimal creditsThisStay(String confirmationNumber) {
        return jdbc.sql("""
                SELECT COALESCE(-SUM(amount), 0) FROM folio_charges
                WHERE upper(confirmation_number) = upper(:c) AND category = 'CREDIT'""")
                .param("c", confirmationNumber.trim()).query(BigDecimal.class).single();
    }

    public void postCredit(String confirmationNumber, BigDecimal amount, String reason, String approvedBy) {
        jdbc.sql("""
                INSERT INTO folio_charges (confirmation_number, charge_date, category, description, amount, posted_by)
                VALUES (:c, CURRENT_DATE, 'CREDIT', :d, :a, :by)""")
                .param("c", confirmationNumber).param("d", truncate("Service recovery credit: " + reason, 500))
                .param("a", amount.negate()).param("by", truncate(approvedBy, 80)).update();
    }

    public void moveReservation(String confirmationNumber, String fromRoom, String toRoom) {
        jdbc.sql("UPDATE reservations SET room_number = :to WHERE confirmation_number = :c")
                .param("to", toRoom).param("c", confirmationNumber).update();
        jdbc.sql("UPDATE rooms SET status = 'OCCUPIED' WHERE room_number = :r").param("r", toRoom).update();
        if (fromRoom != null) {
            jdbc.sql("UPDATE rooms SET status = 'VACANT_DIRTY' WHERE room_number = :r AND status = 'OCCUPIED'")
                    .param("r", fromRoom).update();
        }
    }

    public void setCheckoutTime(String confirmationNumber, String time) {
        jdbc.sql("UPDATE reservations SET checkout_time = CAST(:t AS time) WHERE confirmation_number = :c")
                .param("t", time).param("c", confirmationNumber).update();
    }

    public void log(String operation, String target, String details, String by) {
        jdbc.sql("INSERT INTO operation_log (operation, target, details, performed_by) VALUES (:o, :t, :d, :b)")
                .param("o", operation).param("t", target).param("d", details).param("b", truncate(by, 80)).update();
    }

    /** Agent-written text (justifications) has no length guarantee; never let it fail a write. */
    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
