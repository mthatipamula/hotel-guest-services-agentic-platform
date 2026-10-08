package com.aurora.guestops.hoteldata;

import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loads demo data so reservation dates are relative to today.
 * <ul>
 *   <li>Locally ({@code reset-demo-data=true}): reloaded at every start.</li>
 *   <li>On Cloud Run ({@code false}): loaded only into an empty database, because instances start and stop
 *       with traffic and a reset on every cold start would undo approved actions. Reset explicitly with
 *       {@code POST /admin/demo-data/reset} (IAM-protected).</li>
 * </ul>
 */
@Component
@RestController
@Order(1)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final HotelProperties props;

    public DemoDataSeeder(DataSource dataSource, JdbcClient jdbc, HotelProperties props) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer reservations = jdbc.sql("SELECT count(*) FROM reservations").query(Integer.class).single();
        if (props.resetDemoData() || reservations == null || reservations == 0) {
            reset();
        } else {
            log.info("Keeping existing hotel data ({} reservations)", reservations);
        }
    }

    @PostMapping("/admin/demo-data/reset")
    public Map<String, Object> resetEndpoint() {
        reset();
        return Map.of("status", "reset");
    }

    public void reset() {
        new ResourceDatabasePopulator(new ClassPathResource("demo/seed.sql")).execute(dataSource);
        log.info("Demo hotel data loaded");
    }
}
