package org.volunteers.scheduler;

import java.util.*;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.repository.DeviceVisits;
import org.volunteers.scheduler.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:navigation-tests;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true"})
@AutoConfigureMockMvc
@Transactional
class NavigationIntegrationTest {
    @Autowired SchedulerService service;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired DeviceVisits visits;
    Event event;
    Department department;
    Volunteer owner;
    MockHttpSession session;
    @BeforeEach void setup() {
        event = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-04"));
        department = service.saveDepartment(event.getId(), null, Map.of("name", "Attendants"));
        owner = service.saveVolunteer(event.getId(), null, profile("Alex Actual", "2105550000", "ADMIN"));
        session = new MockHttpSession();
        session.setAttribute(PhoneAccess.SESSION, "2105550000");
        session.setAttribute("activeEvent", event.getId());
    }
    Map<String, String> profile(String name, String phone, String role) {
        return Map.of("name", name, "phone", phone, "congregation", "Central", "privilege", "PUBLISHER",
            "departmentRole_" + department.getId(), role);
    }
    String base() { return "/events/" + event.getId(); }

    @Test void sectionResponsesKeepPermissionsAndOmitSharedShell() throws Exception {
        for (String path : List.of(base() + "/home", base() + "/volunteers", base() + "/schedule",
                base() + "/departments", base() + "/more", "/events", "/logs", "/settings")) {
            String html = mvc.perform(get(path).session(session).header("X-Scheduler-Section", "main"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
            assertThat(html).contains("id=\"main\"", "data-page-title=", "class=\"bottom-nav\"")
                .doesNotContain("<html", "<head>", "class=\"app-header\"", "id=\"notification-dialog\"", "<script");
        }
        mvc.perform(get(base() + "/home").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-header\"")));
        mvc.perform(get(base() + "/home").header("X-Scheduler-Section", "main"))
            .andExpect(redirectedUrl("/login"));
    }

    @Test void volunteerDatabasePagesAreStableFilteredAndSupportOffPageDetails() throws Exception {
        for (int i = 1; i <= 51; i++) service.saveVolunteer(event.getId(), null,
            profile(String.format("Volunteer %03d", i), String.format("210555%04d", i), "VOLUNTEER"));
        em.flush(); em.clear();
        var first = service.volunteerPage(event.getId(), null, "", 0, List.of());
        var second = service.volunteerPage(event.getId(), null, "", 1, List.of());
        assertThat(first.page().getTotalElements()).isEqualTo(52);
        assertThat(first.page().getContent()).hasSize(25);
        assertThat(second.page().getContent()).hasSize(25);
        assertThat(first.page().getContent()).extracting(Volunteer::getId)
            .doesNotContainAnyElementsOf(second.page().getContent().stream().map(Volunteer::getId).toList());
        assertThat(service.volunteerPage(event.getId(), department.getId(), "Volunteer 051", 0, List.of()).page().getContent())
            .extracting(Volunteer::getName).containsExactly("Volunteer 051");
        var last = service.volunteerPage(event.getId(), null, "", 2, List.of());
        assertThat(last.page().getContent()).hasSize(2);
        Long offPage = last.page().getContent().getLast().getId();
        mvc.perform(get(base() + "/volunteers").session(session).param("view", offPage.toString()))
            .andExpect(status().isOk()).andExpect(model().attribute("openVolunteerId", offPage));
        mvc.perform(get(base() + "/volunteers").session(session).param("departmentId", department.getId().toString()).param("q", "Volunteer"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("page=1")));
        assertThat(service.volunteerPage(event.getId(), null, "", 1000, List.of()).page().getContent()).isEmpty();
    }

    @Test void schedulePagesKeepConflictsOutsideVisiblePage() throws Exception {
        Shift hidden = null;
        for (int i = 0; i < 26; i++) hidden = service.saveShift(event.getId(), null, Map.of("name", "Shift " + i,
            "departmentId", department.getId().toString(), "date", "2027-06-04", "startTime", "09:00",
            "endTime", "10:00", "requiredCount", "1"));
        service.assign(event.getId(), hidden.getId(), owner.getId(), false);
        var response = mvc.perform(get(base() + "/schedule").session(session)).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked") var rows = (List<SchedulerService.ShiftRow>) response.getModelAndView().getModel().get("rows");
        assertThat(rows).hasSize(25);
        @SuppressWarnings("unchecked") var options = (Map<Long, List<Volunteer>>) response.getModelAndView().getModel().get("assignmentOptions");
        options.values().forEach(eligible -> assertThat(eligible).extracting(Volunteer::getId).doesNotContain(owner.getId()));
        mvc.perform(get(base() + "/schedule").session(session).param("page", "1"))
            .andExpect(status().isOk()).andExpect(model().attribute("rows", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test void logNamesComeFromSessionAndSharedDevicesKeepSeparateUsers() throws Exception {
        Cookie device = new Cookie("schedulerDevice", UUID.randomUUID().toString());
        mvc.perform(post("/api/device-visits").session(session).cookie(device).with(csrf())
            .contentType("application/json").content("{\"name\":\"Forged name\",\"browser\":\"Firefox\"}"))
            .andExpect(status().isNoContent());
        assertThat(visits.findAll()).extracting(v -> v.name).containsExactly("Alex Actual");
        service.saveVolunteer(event.getId(), null, profile("Another Person", "2105550999", "VOLUNTEER"));
        var another = new MockHttpSession(); another.setAttribute(PhoneAccess.SESSION, "2105550999");
        mvc.perform(post("/api/device-visits").session(another).cookie(device).with(csrf())
            .contentType("application/json").content("{}"))
            .andExpect(status().isNoContent());
        assertThat(visits.findAll()).extracting(v -> v.name).containsExactlyInAnyOrder("Alex Actual", "Another Person");
        mvc.perform(get("/logs").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Alex Actual")));
    }
}
