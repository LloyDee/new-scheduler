package org.volunteers.scheduler;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real request boundaries: no test transaction or mocked access service. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:access-query-tests;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.generate_statistics=true", "logging.level.org.hibernate.stat=ERROR",
    "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=ERROR"})
@AutoConfigureMockMvc
class AccessQueryIntegrationTest {
    @TestConfiguration
    static class SqlConfiguration {
        @Bean SqlCapture sqlCapture() { return new SqlCapture(); }
        @Bean HibernatePropertiesCustomizer captureSql(SqlCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }
    }
    static class SqlCapture implements StatementInspector {
        final List<String> statements = new CopyOnWriteArrayList<>();
        public String inspect(String sql) { statements.add(sql.toLowerCase(Locale.ROOT)); return sql; }
        long permissionQueries() { return statements.stream().filter(s -> s.startsWith("select") && s.contains("normalized_phone=?")).count(); }
        long eventScans() { return statements.stream().filter(s -> s.startsWith("select") && s.contains(" from scheduler_event ")
            && !s.contains(" where ") && !s.contains(" offset ") && !s.contains(" fetch first ") && !s.contains("count(")).count(); }
        long locks() { return statements.stream().filter(s -> s.contains("for update")).count(); }
    }
    @Autowired SchedulerService scheduler;
    @Autowired MockMvc mvc;
    @Autowired EntityManagerFactory emf;
    @Autowired SqlCapture sql;
    final List<Long> created = new ArrayList<>();
    @AfterEach void cleanup() { for (Long id : created) scheduler.deleteEvent(id); }
    Event event() {
        var result = scheduler.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-04"));
        created.add(result.getId()); return result;
    }
    MockHttpSession session(String phone, long eventId) {
        var session = new MockHttpSession(); session.setAttribute(PhoneAccess.SESSION, phone); session.setAttribute("activeEvent", eventId); return session;
    }
    Map<String,String> profile(String phone, String role, long departmentId) {
        return new HashMap<>(Map.of("name", "Person " + phone, "phone", phone, "congregation", "Central", "privilege", "PUBLISHER",
            "eventAdministrator", Boolean.toString(role.equals("ADMIN")), "departmentRole_" + departmentId, role.equals("ADMIN") ? "VOLUNTEER" : role));
    }
    void reset() { emf.unwrap(SessionFactory.class).getStatistics().clear(); sql.statements.clear(); }
    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "ADMIN", "OVERSEER", "ASSISTANT_OVERSEER", "CAPTAIN", "KEY_MAN", "VOLUNTEER"})
    void navigationQueriesAreBoundedForEveryRole(String role) throws Exception {
        Event event = event();
        var department = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        String phone = role.equals("OWNER") ? "8135160335" : "2105550100";
        if (!role.equals("OWNER")) scheduler.saveVolunteer(event.getId(), null, profile(phone, role, department.getId()));
        scheduler.saveShift(event.getId(), null, Map.of("name", "Morning", "departmentId", department.getId().toString(), "date", "2027-06-04",
            "startTime", "09:00", "endTime", "10:00", "requiredCount", "1"));
        var session = session(phone, event.getId());
        String base = "/events/" + event.getId();
        boolean logsAllowed = Set.of("OWNER", "ADMIN", "OVERSEER", "ASSISTANT_OVERSEER").contains(role);
        for (boolean large : List.of(false, true)) {
            if (large) {
                // Enough events to cross the pagination boundary; extra memberships must not cause N+1 queries.
                for (int i = 0; i < 26; i++) {
                    Event other = event();
                    var d = scheduler.saveDepartment(other.getId(), null, Map.of("name", "Other"));
                    if (!role.equals("OWNER")) scheduler.saveVolunteer(other.getId(), null, profile(phone, "VOLUNTEER", d.getId()));
                }
                for (int i = 0; i < 10; i++) {
                    var d = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Department " + i));
                    scheduler.saveVolunteer(event.getId(), null, profile("210555" + String.format("%04d", 200 + i), "VOLUNTEER", d.getId()));
                }
            }
            for (String path : List.of(base + "/schedule", "/events", "/settings", "/logs")) {
                for (boolean partial : List.of(false, true)) {
                    reset();
                    var request = get(path).session(session);
                    if (partial) request.header("X-Scheduler-Section", "main");
                    mvc.perform(request).andExpect(status().is(path.equals("/logs") && !logsAllowed ? 403 : 200));
                    long count = emf.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
                    System.out.printf("ACCESS_SQL role=%s large=%s partial=%s path=%s total=%d permissions=%d eventScans=%d%n",
                        role, large, partial, path.substring(path.lastIndexOf('/')), count, sql.permissionQueries(), sql.eventScans());
                    assertThat(sql.permissionQueries()).as("one access lookup per request").isEqualTo(1);
                    assertThat(sql.eventScans()).as("no unbounded event scans for permissions").isZero();
                    long budget = path.endsWith("/schedule") ? 6 : path.equals("/events") && large ? 4 : 2;
                    assertThat(count).as(role + " " + path + " SQL budget").isLessThanOrEqualTo(budget);
                    assertThat(sql.statements.stream().filter(statement -> statement.startsWith("select")).toList())
                        .as("no duplicate SELECT statements in this request").doesNotHaveDuplicates();
                }
            }
        }
    }
    @Test void managedVolunteerWritesAcquireOnlyOneEventLock() throws Exception {
        Event event = event();
        var department = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = profile("2105550199", "VOLUNTEER", department.getId());
        var volunteer = scheduler.saveVolunteer(event.getId(), null, input);
        var session = session("8135160335", event.getId());
        input.put("name", "Updated member");
        reset();
        var request = post("/events/" + event.getId() + "/volunteers/save").session(session).with(csrf()).param("id", volunteer.getId().toString());
        input.forEach(request::param);
        mvc.perform(request).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice"));
        System.out.printf("ACCESS_WRITE save total=%d locks=%d%n", sql.statements.size(), sql.locks());
        assertThat(sql.locks()).as("save event lock").isEqualTo(1);
        assertThat(sql.permissionQueries()).isEqualTo(1);
        assertThat(sql.statements.stream().filter(statement -> statement.startsWith("select")).toList()).doesNotHaveDuplicates();
        assertThat(scheduler.volunteer(event.getId(), volunteer.getId()).getName()).isEqualTo("Updated member");
        reset();
        mvc.perform(post("/events/" + event.getId() + "/volunteers/" + volunteer.getId() + "/delete").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice"));
        System.out.printf("ACCESS_WRITE delete total=%d locks=%d%n", sql.statements.size(), sql.locks());
        assertThat(sql.locks()).as("delete event lock").isEqualTo(1);
        assertThat(sql.permissionQueries()).isEqualTo(1);
        assertThat(sql.statements.stream().filter(statement -> statement.startsWith("select")).toList()).doesNotHaveDuplicates();
    }
}
