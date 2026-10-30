package org.volunteers.scheduler;

import java.util.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.volunteers.scheduler.service.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** No enclosing transaction: count the separate persistence contexts of real HTTP requests. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:request-query-tests;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.generate_statistics=true", "logging.level.org.hibernate.stat=ERROR",
    "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=ERROR"})
@AutoConfigureMockMvc
class RequestQueryIntegrationTest {
    @Autowired SchedulerService service;
    @Autowired MockMvc mvc;
    @Autowired EntityManagerFactory emf;

    @Test void navigationHasBoundedQueryCounts() throws Exception {
        var event = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-04"));
        var department = service.saveDepartment(event.getId(), null, Map.of("name", "Attendants"));
        var volunteer = service.saveVolunteer(event.getId(), null, Map.of("name", "Alex", "phone", "2105550100",
            "congregation", "Central", "privilege", "PUBLISHER", "departmentRole_" + department.getId(), "OVERSEER"));
        var otherDepartment = service.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        for (int i = 1; i <= 12; i++) {
            service.saveVolunteer(event.getId(), null, Map.of("name", "Volunteer " + i,
                "phone", String.format("210555%04d", 100 + i), "congregation", "Central", "privilege", "PUBLISHER",
                "departmentRole_" + (i % 2 == 0 ? department.getId() : otherDepartment.getId()), "VOLUNTEER"));
        }
        var shift = service.saveShift(event.getId(), null, Map.of("name", "Welcome", "departmentId", department.getId().toString(),
            "date", "2027-06-04", "startTime", "09:00", "endTime", "10:00", "requiredCount", "1"));
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        var otherEvent = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-11"));
        service.saveVolunteer(otherEvent.getId(), null, Map.of("name", "Alex", "phone", "2105550100",
            "congregation", "Central", "privilege", "PUBLISHER", "departmentChoice", "none"));
        var session = new MockHttpSession();
        session.setAttribute(PhoneAccess.SESSION, "2105550100");
        session.setAttribute("activeEvent", event.getId());
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        Map<String, Long> counts = new LinkedHashMap<>();
        String base = "/events/" + event.getId();
        for (String path : List.of("/events", base + "/home", base + "/volunteers", base + "/schedule",
                base + "/departments", base + "/more", "/settings", "/api/notifications/count")) {
            statistics.clear();
            var response = mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn();
            if (path.endsWith("/departments")) assertThat(response.getResponse().getContentAsString())
                .contains("<summary>View coverage</summary>");
            counts.put(path, statistics.getPrepareStatementCount());
        }
        assertThat(counts).allSatisfy((path, count) -> {
            long budget = path.endsWith("/more") || path.equals("/events") || path.equals("/settings")
                || path.startsWith("/api/") ? 2 : 6;
            assertThat(count).as(path + " SQL statements").isLessThanOrEqualTo(budget);
        });
        for (String path : List.of(base + "/volunteers?edit=" + volunteer.getId(),
                base + "/volunteers?view=" + volunteer.getId(), base + "/departments?view=" + department.getId(),
                base + "/departments?edit=" + department.getId(), base + "/schedule?edit=" + shift.getId())) {
            statistics.clear();
            mvc.perform(get(path).session(session)).andExpect(status().isOk());
            assertThat(statistics.getPrepareStatementCount()).as(path).isEqualTo(6);
        }
        statistics.clear();
        mvc.perform(post(base + "/select").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(statistics.getPrepareStatementCount()).as("event selection POST").isEqualTo(2);
        statistics.clear();
        mvc.perform(post("/settings").param("timeFormat", "24").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(statistics.getPrepareStatementCount()).as("preferences POST").isEqualTo(1);
        var rolelessSession = new MockHttpSession();
        rolelessSession.setAttribute(PhoneAccess.SESSION, "2105550100");
        rolelessSession.setAttribute("activeEvent", otherEvent.getId());
        mvc.perform(get("/events/" + otherEvent.getId() + "/departments").param("create", "true").session(rolelessSession))
            .andExpect(status().isForbidden());
        service.saveVolunteer(otherEvent.getId(), null, Map.of("name", "New volunteer", "phone", "2105550199",
            "congregation", "Central", "privilege", "PUBLISHER", "departmentChoice", "none"));
        var newcomerSession = new MockHttpSession();
        newcomerSession.setAttribute(PhoneAccess.SESSION, "2105550199");
        mvc.perform(get("/events").session(newcomerSession)).andExpect(status().isOk())
            .andExpect(model().attribute("canCreateEvent", false));
        // Reusing the session must not retain permissions from a previous request.
        service.saveVolunteer(event.getId(), volunteer.getId(), Map.of("name", "Alex", "phone", "2105550100",
            "congregation", "Central", "privilege", "PUBLISHER", "departmentRole_" + department.getId(), "VOLUNTEER"));
        mvc.perform(get(base + "/departments").param("create", "true").session(session)).andExpect(status().isForbidden());
        assertThat(counts.get(base + "/more")).isEqualTo(2);
        assertThat(counts.get("/events")).isEqualTo(2);
        assertThat(counts.get(base + "/home")).isEqualTo(6);
    }
}
