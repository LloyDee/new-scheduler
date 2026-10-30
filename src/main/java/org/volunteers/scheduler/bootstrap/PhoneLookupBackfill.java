package org.volunteers.scheduler.bootstrap;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.volunteers.scheduler.service.PhoneAccess;

/** Populate the lookup column for records created before it existed. */
@Component
public class PhoneLookupBackfill implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public PhoneLookupBackfill(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        List<Object[]> rows = jdbc.query(
            "select id, phone from volunteer where normalized_phone is null",
            (rs, row) -> new Object[] { PhoneAccess.normalize(rs.getString("phone")), rs.getLong("id") });
        jdbc.batchUpdate("update volunteer set normalized_phone = ? where id = ? and normalized_phone is null", rows);
    }
}
