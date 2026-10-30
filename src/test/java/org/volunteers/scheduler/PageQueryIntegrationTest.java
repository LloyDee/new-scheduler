package org.volunteers.scheduler;

import java.util.Map;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.volunteers.scheduler.service.SchedulerService;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:page-query-tests;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.generate_statistics=true", "logging.level.org.hibernate.stat=ERROR",
    "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=ERROR"})
@Transactional
class PageQueryIntegrationTest {
    @Autowired SchedulerService service;
    @Autowired EntityManager em;

    @Test void pageSnapshotDoesNotQueryRolesForEachVolunteer() {
        var event = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-04"));
        var department = service.saveDepartment(event.getId(), null, Map.of("name", "Attendants"));
        for (int i = 0; i < 12; i++) {
            service.saveVolunteer(event.getId(), null, Map.of("name", "Volunteer " + i,
                "phone", String.format("210555%04d", i), "congregation", "Central", "privilege", "PUBLISHER",
                "departmentRole_" + department.getId(), "VOLUNTEER"));
        }
        em.flush();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var snapshot = service.snapshot(event.getId());
        assertThat(snapshot.volunteers()).hasSize(13);
        snapshot.volunteers().stream().filter(v -> !v.isEventAdministrator()).forEach(v -> assertThat(v.getDepartmentRoles()).hasSize(1));
        assertThat(statistics.getPrepareStatementCount()).as("SQL statements to load one page snapshot").isEqualTo(5);
    }
}
