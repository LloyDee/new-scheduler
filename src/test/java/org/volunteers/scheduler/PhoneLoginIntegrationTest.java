package org.volunteers.scheduler;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:phone-tests;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
@Transactional
class PhoneLoginIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SchedulerService service;
    @Autowired PhoneAccess access;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.volunteers.scheduler.bootstrap.PhoneLookupBackfill phoneBackfill;
    Event event;
    Department department;
    Volunteer volunteer;
    @BeforeEach void setup() {
        event = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-04"));
        department = service.saveDepartment(event.getId(), null, Map.of("name", "Attendants"));
        var input = profile("2105550100"); input.put("departmentRole_" + department.getId(), "VOLUNTEER");
        volunteer = service.saveVolunteer(event.getId(), null, input);
    }
    Map<String,String> profile(String phone) {
        return new HashMap<>(Map.of("name", "Alex", "phone", phone, "email", "", "congregation", "Central", "privilege", "PUBLISHER"));
    }
    MockHttpSession session() {
        var session = new MockHttpSession(); session.setAttribute(PhoneAccess.SESSION, "2105550100");
        session.setAttribute("activeEvent", event.getId()); return session;
    }
    String base() { return "/events/" + event.getId(); }
    @Test void greetingUsesOnlyFirstNameBesideLogout() throws Exception {
        var input = profile("2105550100");
        input.put("name", "Lloyd Jayson Pintac");
        input.put("departmentRole_" + department.getId(), "VOLUNTEER");
        service.saveVolunteer(event.getId(), volunteer.getId(), input);
        mvc.perform(get("/events").session(session())).andExpect(status().isOk())
            .andExpect(model().attribute("currentUserFirstName", "Lloyd"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("<span>Hi Lloyd</span>")));
    }
    @Test void greetingLinksToOwnEventCardEvenWhenSearchAndDepartmentHideIt() throws Exception {
        var otherDepartment = service.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        mvc.perform(get(base() + "/volunteers").session(session()).param("q", "no-match")
                .param("departmentId", otherDepartment.getId().toString()).param("page", "5"))
            .andExpect(status().isOk()).andExpect(model().attribute("currentVolunteerId", volunteer.getId()))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("view=" + volunteer.getId())))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-own-volunteer-link")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-volunteer-search")));
        mvc.perform(get(base() + "/volunteers").session(session()).param("view", volunteer.getId().toString())
                .param("q", "no-match").param("departmentId", otherDepartment.getId().toString()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-open-on-load=\"true\"")));
        var other = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-07-02"));
        mvc.perform(get("/events/" + other.getId() + "/volunteers").session(session()))
            .andExpect(status().isOk()).andExpect(model().attributeDoesNotExist("currentVolunteerId"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-own-volunteer-link"))));
    }
    @Test void existingPhoneRecordsAreBackfilledAndEditsUpdateLookup() {
        jdbc.update("update volunteer set normalized_phone = null where id = ?", volunteer.getId());
        phoneBackfill.run(null);
        phoneBackfill.run(null);
        assertThat(access.matching("+1 (210) 555-0100")).extracting(Volunteer::getId).contains(volunteer.getId());
        var edited = profile("2105550102");
        edited.put("departmentRole_" + department.getId(), "VOLUNTEER");
        service.saveVolunteer(event.getId(), volunteer.getId(), edited);
        assertThat(access.matching("2105550100")).isEmpty();
        assertThat(access.matching("2105550102")).extracting(Volunteer::getId).contains(volunteer.getId());
    }
    @Test void searchFindsVolunteersBeyondFirstPageAcrossAllSearchFields() throws Exception {
        for (int i = 0; i < 26; i++) {
            var input = profile("21055502" + String.format("%02d", i));
            input.put("name", "Before " + i);
            input.put("departmentChoice", "none");
            service.saveVolunteer(event.getId(), null, input);
        }
        var target = profile("2105550999");
        target.put("name", "Zulu Person");
        target.put("departmentChoice", "none");
        target.put("congregation", "Unique Congregation");
        target.put("email", "unique@example.com");
        var record = service.saveVolunteer(event.getId(), null, target);
        assertThat(service.volunteerPage(event.getId(), null, "", 0, List.of()).page().getContent())
            .extracting(Volunteer::getId).doesNotContain(record.getId());
        for (String query : List.of("zULu", "Unique Congregation", "555-0999", "unique@example.com")) {
            mvc.perform(get(base() + "/volunteers").session(session()).param("departmentId", "").param("q", query))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 volunteers")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Zulu Person")));
        }
    }
    @Test void anonymousMustLoginAndPublicFormsRender() throws Exception {
        mvc.perform(get("/events")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/signup")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Add a volunteer")));
    }
    @Test void normalizedGlobalLoginAndUnknownNumber() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("phone", "+1 (210) 555-0100"))
            .andExpect(redirectedUrl("/events")).andExpect(request().sessionAttribute(PhoneAccess.SESSION, "2105550100"));
        mvc.perform(post("/login").with(csrf()).param("phone", "2105550199"))
            .andExpect(redirectedUrl("/login")).andExpect(flash().attribute("error", "Phone number not found. Please sign up."));
    }
    @Test void signupRejectsGlobalDuplicateAndCannotGrantRoles() {
        Event other = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-11"));
        assertThatThrownBy(() -> access.signup(other.getId(), profile("+1 (210) 555-0100")))
            .hasMessageContaining("already exists");
        var input = profile("2105550101"); input.put("departmentRole_" + department.getId(), "ADMIN"); input.put("eventAdministrator", "true");
        Volunteer created = access.signup(event.getId(), input);
        assertThat(created.getDepartmentRoles()).isEmpty();
        assertThat(created.isEventAdministrator()).isFalse();
    }
    @Test void volunteerCanEditOnlySelfAndCannotPromoteSelf() throws Exception {
        mvc.perform(post(base() + "/volunteers/save").session(session()).with(csrf())
            .param("id", volunteer.getId().toString()).param("name", "Updated").param("phone", "2105550100")
            .param("congregation", "Central").param("departmentRole_" + department.getId(), "ADMIN"))
            .andExpect(status().is3xxRedirection());
        assertThat(service.volunteer(event.getId(), volunteer.getId()).getName()).isEqualTo("Updated");
        assertThat(access.canManage(session(), event.getId())).isFalse();
        mvc.perform(post(base() + "/volunteers/save").session(session()).with(csrf()).param("id", "999999"))
            .andExpect(status().isForbidden());
        mvc.perform(get(base() + "/volunteers").session(session()).param("edit", volunteer.getId().toString()))
            .andExpect(status().isOk());
    }
    @Test void managementPostsAndEditorUrlsAreDenied() throws Exception {
        for (String path : List.of("/volunteers/save", "/departments/save", "/schedule/save", "/schedule/1/assignments",
                "/schedule/1/assign", "/schedule/1/assign-batch", "/backups/restore", "/volunteers/1/delete")) {
            mvc.perform(post(base() + path).session(session()).with(csrf())).andExpect(status().isForbidden());
        }
        for (String path : List.of("/settings/data/volunteers/import", "/settings/notifications", "/events/save"))
            mvc.perform(post(path).session(session()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(base() + "/schedule").session(session()).param("create", "true")).andExpect(status().isForbidden());
    }
    @Test void departmentManagementRolesAreAllowedOnlyInTheirEvent() {
        Event other = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-11"));
        for (Volunteer.Role role : Volunteer.Role.values()) {
            if (role == Volunteer.Role.ADMIN) continue; // Admin is now an event permission.
            var input = profile("2105550100"); input.put("departmentRole_" + department.getId(), role.name());
            service.saveVolunteer(event.getId(), volunteer.getId(), input);
            assertThat(access.canManage(session(), event.getId())).isEqualTo(Set.of(Volunteer.Role.ADMIN,
                Volunteer.Role.OVERSEER, Volunteer.Role.ASSISTANT_OVERSEER, Volunteer.Role.CAPTAIN).contains(role));
            assertThat(access.canManage(session(), other.getId())).isFalse();
        }
    }
    @Test void departmentDefaultsCanBeClearedAndPagesRender() throws Exception {
        for (String page : List.of("/volunteers", "/schedule")) {
            mvc.perform(get(base() + page).session(session())).andExpect(status().isOk())
                .andExpect(model().attribute("departmentId", department.getId()));
            mvc.perform(get(base() + page).session(session()).param("departmentId", ""))
                .andExpect(status().isOk()).andExpect(model().attribute("departmentId", org.hamcrest.Matchers.nullValue()));
        }
        for (String page : List.of("/home", "/departments", "/reports", "/more"))
            mvc.perform(get(base() + page).session(session())).andExpect(status().isOk());
        mvc.perform(get("/events").session(session())).andExpect(status().isOk());
        mvc.perform(get("/settings").session(session())).andExpect(status().isOk());
    }
    @Test void personalShiftsAndHighlightUseIdentityNotName() throws Exception {
        var shift = service.saveShift(event.getId(), null, Map.of("name", "Welcome", "departmentId", department.getId().toString(),
            "date", "2027-06-04", "startTime", "09:00", "endTime", "10:00", "requiredCount", "2"));
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        mvc.perform(get(base() + "/home").session(session())).andExpect(status().isOk())
            .andExpect(model().attribute("upcoming", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("current-user-name")));
    }
    @Test void logoutEndsSessionAndCsrfIsRequired() throws Exception {
        mvc.perform(post("/login").param("phone", "2105550100")).andExpect(status().isForbidden());
        var session = session();
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login"));
        assertThat(session.isInvalid()).isTrue();
    }
}
