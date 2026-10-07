package com.aurora.guestops.hoteldata;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** Reloads demo data at startup so reservation dates are always relative to today. */
@Component
@Order(1)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DataSource dataSource;
    private final HotelProperties props;

    public DemoDataSeeder(DataSource dataSource, HotelProperties props) {
        this.dataSource = dataSource;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.resetDemoData()) {
            return;
        }
        reset();
    }

    public void reset() {
        new ResourceDatabasePopulator(new ClassPathResource("demo/seed.sql")).execute(dataSource);
        log.info("Demo hotel data loaded");
    }
}
