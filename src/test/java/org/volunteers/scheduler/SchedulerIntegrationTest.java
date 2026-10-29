package org.volunteers.scheduler;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:scheduler-tests;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Transactional
class SchedulerIntegrationTest {
    @Autowired SchedulerService service;
    @Autowired MockMvc mvc;
    @Autowired Events events;
    @Autowired Volunteers volunteers;
    @Autowired Departments departments;
    @Autowired Shifts shifts;
    @Autowired Assignments assignments;
    @Autowired jakarta.persistence.EntityManager entityManager;
    Event event;
    Department department;
    Volunteer volunteer;
    Shift shift;

    @BeforeEach void setup() {
        event = event("Regional", "REGIONAL_CONVENTION");
        department = service.saveDepartment(event.id, null, Map.of("name", "Attendants", "description", "Welcome team"));
        volunteer = service.saveVolunteer(event.id, null, volunteerInput("name", "Alex Rivera", "congregation", "North", "email", "alex@example.com"));
        shift = service.saveShift(event.id, null, shiftInput("09:00", "10:00"));
    }

    @Test void eventCreationDerivesNamesDatesAndValidatesRepresentative() {
        for (String date : List.of("2026-08-31", "2026-09-01", "2026-12-31")) {
            Event created = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", date));
            assertThat(created.name).isEqualTo((date.equals("2026-08-31") ? "2026" : "2027") + " Regional Convention");
            assertThat(created.endDate).isEqualTo(LocalDate.parse(date).plusDays(3));
        }
        for (String representative : List.of("with Circuit Overseer", "with Branch Representative")) {
            Event created = service.saveEvent(null, Map.of("type", "CIRCUIT_ASSEMBLY", "startDate", "2026-10-28", "assembly", representative));
            assertThat(created.name).isEqualTo("Circuit Assembly " + representative);
            assertThat(created.endDate).isEqualTo(created.startDate);
        }
        assertThatThrownBy(() -> service.saveEvent(null, Map.of("type", "CIRCUIT_ASSEMBLY", "startDate", "2026-10-28")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void eventEditorOnlyAppearsWhenOpened() throws Exception {
        mvc.perform(get("/events")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-event-form"))));
        mvc.perform(get("/events?create=true")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-event-form")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"name\""))));
    }

    @Test void homeShowsWeatherWithoutSelectingAnEvent() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-weather")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Create event"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("BUILD YOUR EVENT"))));
        mvc.perform(get("/events/" + event.id + "/home"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-weather")));
    }

    @Test void rendersEveryPageWithRealDataAndCsrfFields() throws Exception {
        service.assign(event.id, shift.id, volunteer.id, false);
        String base = "/events/" + event.id;
        for (String path : List.of("/events", "/events?edit=" + event.id, "/settings", base + "/home", base + "/more",
                base + "/volunteers", base + "/volunteers?edit=" + volunteer.id, base + "/volunteers?view=" + volunteer.id,
                base + "/departments", base + "/departments?edit=" + department.id, base + "/departments?view=" + department.id,
                base + "/schedule", base + "/schedule?edit=" + shift.id,
                base + "/reports", base + "/reports?type=volunteer", base + "/reports?type=coverage")) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Gather")));
        }
        mvc.perform(get(base + "/schedule")).andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Alex Rivera")));
    }

    @Test void eventsAreIsolatedForReadsWritesAssignmentsAndFilters() throws Exception {
        Event other = event("Assembly", "CIRCUIT_ASSEMBLY");
        assertThatThrownBy(() -> service.volunteer(other.id, volunteer.id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.assign(other.id, shift.id, volunteer.id, false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.saveShift(other.id, null, shiftInput("10:00", "11:00"))).isInstanceOf(ResponseStatusException.class);
        mvc.perform(get("/events/" + other.id + "/volunteers?edit=" + volunteer.id)).andExpect(status().isNotFound());
        mvc.perform(get("/events/" + other.id + "/schedule?departmentId=" + department.id)).andExpect(status().isNotFound());
        mvc.perform(get("/events/" + other.id + "/reports.csv?departmentId=" + department.id)).andExpect(status().isNotFound());
        mvc.perform(get("/events/" + other.id + "/volunteers")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Alex Rivera"))));
    }

    @Test void departmentFilterCombinesWithVolunteerSearch() throws Exception {
        Department parking = service.saveDepartment(event.id, null, Map.of("name", "Parking"));
        service.saveVolunteer(event.id, null, volunteerInput("name", "Parking Person",
                "departmentRole_" + parking.id, "VOLUNTEER"));
        mvc.perform(get("/events/" + event.id + "/volunteers").param("departmentId", department.id.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Alex Rivera")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Parking Person"))));
        mvc.perform(get("/events/" + event.id + "/volunteers").param("departmentId", parking.id.toString()).param("q", "Rivera"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No volunteers to show")));
    }

    @Test void assignmentDropdownOnlyContainsDepartmentMembersWithoutOverlaps() throws Exception {
        Department parking = service.saveDepartment(event.id, null, Map.of("name", "Parking"));
        Volunteer other = service.saveVolunteer(event.id, null, volunteerInput("name", "Parking Person",
                "departmentRole_" + parking.id, "VOLUNTEER"));
        Volunteer available = service.saveVolunteer(event.id, null, volunteerInput("name", "Available Person"));
        var busyInput = shiftInput("09:30", "10:30");
        busyInput.put("departmentId", parking.id.toString());
        Shift busy = service.saveShift(event.id, null, busyInput);
        service.assign(event.id, busy.id, volunteer.id, false);
        Shift adjacent = service.saveShift(event.id, null, shiftInput("10:30", "11:00"));
        var data = service.snapshot(event.id);
        assertThat(data.availableVolunteers(shift)).extracting(v -> v.id).containsExactly(available.id);
        assertThat(data.availableVolunteers(adjacent)).extracting(v -> v.id)
                .containsExactlyInAnyOrder(volunteer.id, available.id).doesNotContain(other.id);
        String html = mvc.perform(get("/events/" + event.id + "/schedule"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String card = html.substring(html.indexOf("id=\"shift-" + shift.id + "\""));
        card = card.substring(0, card.indexOf("</article>"));
        assertThat(card).contains("Available Person").doesNotContain("Alex Rivera", "Parking Person");
        service.assign(event.id, shift.id, available.id, false);
        assertThat(service.snapshot(event.id).availableVolunteers(shift)).isEmpty();
        mvc.perform(get("/events/" + event.id + "/schedule"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No available volunteers in this department")));
    }

    @Test void overlapRequiresConfirmationButAdjacentAndOverstaffedShiftsAreAllowed() {
        service.assign(event.id, shift.id, volunteer.id, false);
        Shift overlapping = service.saveShift(event.id, null, shiftInput("09:30", "10:30"));
        assertThatThrownBy(() -> service.assign(event.id, overlapping.id, volunteer.id, false))
                .isInstanceOf(SchedulerService.OverlapException.class).hasMessageContaining("Attendants");
        service.assign(event.id, overlapping.id, volunteer.id, true);
        Shift adjacent = service.saveShift(event.id, null, shiftInput("10:30", "11:00"));
        service.assign(event.id, adjacent.id, volunteer.id, false);
        assertThatThrownBy(() -> service.assign(event.id, shift.id, volunteer.id, true)).hasMessageContaining("already assigned");
        Volunteer second = service.saveVolunteer(event.id, null, volunteerInput("name", "Jamie"));
        service.assign(event.id, shift.id, second.id, false);
        var row = service.snapshot(event.id).rows().stream().filter(r -> r.shift().id.equals(shift.id)).findFirst().orElseThrow();
        assertThat(row.getStatus()).isEqualTo("Overfilled"); assertThat(row.getMissing()).isZero();
    }

    @Test void changingAssignedShiftChecksConflictsAndRetainsOriginalOnFailure() {
        Shift later = service.saveShift(event.id, null, shiftInput("10:00", "11:00"));
        service.assign(event.id, shift.id, volunteer.id, false); service.assign(event.id, later.id, volunteer.id, false);
        var input = shiftInput("09:30", "10:30");
        assertThatThrownBy(() -> service.saveShift(event.id, later.id, input)).isInstanceOf(SchedulerService.OverlapException.class);
        assertThat(service.shift(event.id, later.id).startTime).isEqualTo(LocalTime.of(10, 0));
        input.put("confirmOverlap", "true"); service.saveShift(event.id, later.id, input);
        assertThat(service.shift(event.id, later.id).startTime).isEqualTo(LocalTime.of(9, 30));
    }

    @Test void assemblyShiftsUseEventDateAndRequireTenMinuteTimes() throws Exception {
        Event assembly = event("Assembly", "CIRCUIT_ASSEMBLY");
        Department assemblyDepartment = service.saveDepartment(assembly.id, null, Map.of("name", "Attendants"));
        var input = shiftInput("06:00", "17:30");
        input.put("departmentId", assemblyDepartment.id.toString());
        input.remove("date");
        Shift created = service.saveShift(assembly.id, null, input);
        assertThat(created.date).isEqualTo(assembly.startDate);
        input.put("date", "2031-01-01");
        assertThat(service.saveShift(assembly.id, created.id, input).date).isEqualTo(assembly.startDate);
        for (String invalid : List.of("05:50", "17:40", "06:05", "06:00:01", "")) {
            input.put("startTime", invalid);
            assertThatThrownBy(() -> service.saveShift(assembly.id, null, input)).isInstanceOf(IllegalArgumentException.class);
        }
        input.put("startTime", "06:00");
        for (String invalid : List.of("17:40", "17:25", "")) {
            input.put("endTime", invalid);
            assertThatThrownBy(() -> service.saveShift(assembly.id, null, input)).isInstanceOf(IllegalArgumentException.class);
        }
        String html = mvc.perform(get("/events/" + assembly.id + "/schedule?create=true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String editor = html.substring(html.indexOf("id=\"editor\""));
        assertThat(editor).doesNotContain("name=\"date\"")
                .contains("name=\"startTime\" required", "name=\"endTime\" required", "06:00 AM", "05:30 PM")
                .doesNotContain("05:40 PM", "06:05 AM");
    }

    @Test void shiftNamesAreRequiredSavedAndEditable() throws Exception {
        assertThat(service.shift(event.id, shift.id).getName()).isEqualTo("Morning shift");
        var input = shiftInput("09:00", "10:00");
        input.put("name", "  Afternoon welcome  ");
        service.saveShift(event.id, shift.id, input);
        assertThat(service.shift(event.id, shift.id).getName()).isEqualTo("Afternoon welcome");
        mvc.perform(get("/events/" + event.id + "/schedule?edit=" + shift.id))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Afternoon welcome")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"name\"")));
        for (String invalid : List.of("", "   ", "x".repeat(161))) {
            input.put("name", invalid);
            assertThatThrownBy(() -> service.saveShift(event.id, shift.id, input)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.shift(event.id, shift.id).getName()).isEqualTo("Afternoon welcome");
    }

    @Test void validatesDatesHeadcountsAndEmail() {
        var input = shiftInput("11:00", "10:00");
        assertThatThrownBy(() -> service.saveShift(event.id, null, input)).hasMessageContaining("End time");
        input.putAll(Map.of("endTime", "12:00", "date", "2031-01-01"));
        assertThatThrownBy(() -> service.saveShift(event.id, null, input)).hasMessageContaining("event dates");
        input.putAll(Map.of("date", "2030-06-01", "requiredCount", "0"));
        assertThatThrownBy(() -> service.saveShift(event.id, null, input)).hasMessageContaining("Required volunteers");
        assertThatThrownBy(() -> service.saveVolunteer(event.id, null, volunteerInput("name", "Alex", "email", "bad"))).hasMessageContaining("email");
        var eventInput = eventInput("Regional", "REGIONAL_CONVENTION"); eventInput.put("startDate", "2030-06-02");
        assertThatThrownBy(() -> service.saveEvent(event.id, eventInput)).hasMessageContaining("existing shifts");
    }

    @Test void deletionRemovesDependentsAndPreservesOtherEvents() {
        service.assign(event.id, shift.id, volunteer.id, false);
        Event other = event("Assembly", "CIRCUIT_ASSEMBLY");
        service.deleteVolunteer(event.id, volunteer.id);
        assertThat(service.snapshot(event.id).assignments()).isEmpty();
        assertThat(service.snapshot(event.id).shifts()).hasSize(1);
        service.deleteDepartment(event.id, department.id);
        assertThat(service.snapshot(event.id).shifts()).isEmpty();
        assertThat(service.snapshot(event.id).departments()).isEmpty();
        service.deleteEvent(event.id);
        events.flush();
        assertThat(service.event(other.id).type).isEqualTo(Event.Type.CIRCUIT_ASSEMBLY);
        assertThatThrownBy(() -> service.event(event.id)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void deletingPopulatedEventCleansAllDependents() {
        service.assign(event.id, shift.id, volunteer.id, false);
        service.deleteEvent(event.id); events.flush();
        assertThat(assignments.count()).isZero(); assertThat(shifts.count()).isZero();
        assertThat(departments.count()).isZero(); assertThat(volunteers.count()).isZero(); assertThat(events.count()).isZero();
    }

    @Test void browserFlowPreservesValidationInputAndRequiresCsrf() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/").session(session)).andExpect(status().isOk()).andExpect(view().name("home"));
        mvc.perform(post("/events/save").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/events/" + event.id + "/select").session(session).with(csrf()))
                .andExpect(redirectedUrl("/events/" + event.id + "/home"));
        mvc.perform(get("/").session(session)).andExpect(redirectedUrl("/events/" + event.id + "/home"));
        var result = mvc.perform(post("/events/" + event.id + "/volunteers/save").with(csrf())
                .param("name", "Keep my input").param("email", "bad-email"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error", "draft")).andReturn();
        mvc.perform(get("/events/" + event.id + "/volunteers").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Keep my input")));
    }

    @Test void overlapConfirmationPageRendersAndExplicitPostWorks() throws Exception {
        service.assign(event.id, shift.id, volunteer.id, false);
        Shift overlapping = service.saveShift(event.id, null, shiftInput("09:30", "10:30"));
        String url = "/events/" + event.id + "/schedule/" + overlapping.id + "/assign";
        var result = mvc.perform(post(url).with(csrf()).param("volunteerId", volunteer.id.toString()))
                .andExpect(flash().attributeExists("pending", "error")).andReturn();
        mvc.perform(get("/events/" + event.id + "/schedule").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Confirm overlapping assignment")));
        mvc.perform(post(url).with(csrf()).param("volunteerId", volunteer.id.toString()).param("confirmOverlap", "true"))
                .andExpect(flash().attribute("notice", "Volunteer assigned."));
        assertThat(service.snapshot(event.id).assignments()).hasSize(2);
    }

    @Test void reportsFilterAndCsvEscapesUntrustedValues() throws Exception {
        service.saveVolunteer(event.id, volunteer.id, volunteerInput("name", "=SUM(1,2)\"é"));
        service.assign(event.id, shift.id, volunteer.id, false);
        String base = "/events/" + event.id;
        mvc.perform(get(base + "/reports.csv").param("type", "volunteer"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"'=SUM(1,2)\"\"é\"")));
        mvc.perform(get(base + "/reports?type=coverage&date=2030-06-02"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("No shifts match")));
        mvc.perform(get(base + "/reports?type=unknown")).andExpect(status().isBadRequest());
        mvc.perform(get(base + "/volunteers?q=nomatch")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No volunteers to show")));
    }

    @Test void settingsPersistsCookieAndTimeFormattingChanges() throws Exception {
        mvc.perform(post("/settings").with(csrf()).param("timeFormat", "24"))
                .andExpect(cookie().value("schedulerTimeFormat", "24")).andExpect(cookie().httpOnly("schedulerTimeFormat", true));
        mvc.perform(get("/events/" + event.id + "/schedule").cookie(new jakarta.servlet.http.Cookie("schedulerTimeFormat", "24")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("09:00")));
    }

    @Test void volunteerDepartmentsRolesAndPrivilegePersistAndRender() throws Exception {
        Department second = service.saveDepartment(event.id, null, Map.of("name", "Parking"));
        String firstKey = "departmentRole_" + department.id;
        String secondKey = "departmentRole_" + second.id;
        mvc.perform(post("/events/" + event.id + "/volunteers/save").with(csrf())
                .param("id", volunteer.id.toString()).param("name", "Alex Rivera")
                .param("phone", "202-555-0100").param("email", "alex@example.com").param("congregation", "North")
                .param("privilege", "ELDER").param(firstKey, "OVERSEER").param(secondKey, "VOLUNTEER"))
                .andExpect(flash().attribute("notice", "Volunteer saved."));
        entityManager.flush(); entityManager.clear();
        Volunteer saved = service.volunteer(event.id, volunteer.id);
        assertThat(saved.getPrivilege()).isEqualTo(Volunteer.Privilege.ELDER);
        assertThat(saved.getDepartmentRoles().values()).containsExactlyInAnyOrder(Volunteer.Role.OVERSEER, Volunteer.Role.VOLUNTEER);
        mvc.perform(get("/events/" + event.id + "/volunteers?edit=" + volunteer.id))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Attendants: Overseer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Parking: Volunteer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"ELDER\" selected")));
        service.saveVolunteer(event.id, volunteer.id, volunteerInput("name", "Alex", "privilege", "PUBLISHER", secondKey, "ADMIN"));
        entityManager.flush(); entityManager.clear();
        assertThat(service.volunteer(event.id, volunteer.id).departmentRoles.values()).containsExactly(Volunteer.Role.ADMIN);
        service.deleteDepartment(event.id, second.id);
        entityManager.flush(); entityManager.clear();
        assertThat(service.volunteer(event.id, volunteer.id).departmentRoles).isEmpty();
    }

    @Test void volunteerRolesValidateAndPreserveFailedFormInput() throws Exception {
        String key = "departmentRole_" + department.id;
        assertThatThrownBy(() -> service.saveVolunteer(event.id, null, volunteerInput("name", "Alex", key, "UNKNOWN")))
                .hasMessageContaining("valid role");
        assertThatThrownBy(() -> service.saveVolunteer(event.id, null, volunteerInput("name", "Alex", "privilege", "UNKNOWN")))
                .hasMessageContaining("valid privilege");
        Event other = event("Other", "CIRCUIT_ASSEMBLY");
        assertThatThrownBy(() -> service.saveVolunteer(other.id, null, volunteerInput("name", "Alex", key, "ADMIN")))
                .isInstanceOf(ResponseStatusException.class);
        var result = mvc.perform(post("/events/" + event.id + "/volunteers/save").with(csrf())
                .param("name", "Alex").param("email", "invalid").param("privilege", "MINISTERIAL_SERVANT")
                .param(key, "ASSISTANT_OVERSEER"))
                .andExpect(flash().attributeExists("error", "draft")).andReturn();
        mvc.perform(get("/events/" + event.id + "/volunteers").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"ASSISTANT_OVERSEER\" selected")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"MINISTERIAL_SERVANT\" selected")));
    }

    @Test void allVolunteerFieldsExceptNotesAreRequired() {
        for (String key : List.of("name", "phone", "email", "congregation", "privilege", "departmentRole_" + department.id)) {
            var input = volunteerInput();
            input.put(key, "   ");
            assertThatThrownBy(() -> service.saveVolunteer(event.id, null, input))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.saveVolunteer(event.id, null, volunteerInput()).notes).isEmpty();
    }

    @Test void leadershipLimitsApplyToCreationAndEditingPerDepartment() {
        String key = "departmentRole_" + department.id;
        service.saveVolunteer(event.id, volunteer.id, volunteerInput(key, "OVERSEER"));
        service.saveVolunteer(event.id, volunteer.id, volunteerInput(key, "OVERSEER"));
        assertThatThrownBy(() -> service.saveVolunteer(event.id, null, volunteerInput(key, "OVERSEER")))
                .hasMessageContaining("at most 1 Overseer");
        service.saveVolunteer(event.id, null, volunteerInput(key, "ASSISTANT_OVERSEER"));
        service.saveVolunteer(event.id, null, volunteerInput(key, "ASSISTANT_OVERSEER"));
        assertThatThrownBy(() -> service.saveVolunteer(event.id, null, volunteerInput(key, "ASSISTANT_OVERSEER")))
                .hasMessageContaining("at most 2 Assistant Overseers");
        assertThatThrownBy(() -> service.saveVolunteer(event.id, volunteer.id, volunteerInput(key, "ASSISTANT_OVERSEER")))
                .hasMessageContaining("at most 2 Assistant Overseers");
        assertThat(service.volunteer(event.id, volunteer.id).departmentRoles.values()).containsExactly(Volunteer.Role.OVERSEER);
        Department other = service.saveDepartment(event.id, null, Map.of("name", "Parking"));
        service.saveVolunteer(event.id, null, volunteerInput("departmentRole_" + other.id, "OVERSEER"));
    }

    @Test void testDataHasRequestedMembershipsAndIsRepeatSafe() {
        TestVolunteerData seed = new TestVolunteerData(service);
        seed.run();
        seed.run();
        Event seeded = service.events().stream().filter(e -> "Volunteer Test Event".equals(e.name)).findFirst().orElseThrow();
        var data = service.snapshot(seeded.id);
        assertThat(data.volunteers()).hasSize(100);
        assertThat(data.volunteers().stream().filter(v -> v.departmentRoles.size() > 1).count()).isEqualTo(10);
        for (Department d : data.departments()) {
            assertThat(data.volunteers().stream().filter(v -> v.departmentRoles.get(d) == Volunteer.Role.OVERSEER).count()).isEqualTo(1);
            assertThat(data.volunteers().stream().filter(v -> v.departmentRoles.get(d) == Volunteer.Role.ASSISTANT_OVERSEER).count()).isEqualTo(2);
        }
        assertThat(service.snapshot(event.id).volunteers()).hasSize(1);
    }

    private Map<String, String> volunteerInput(String... values) {
        Map<String, String> input = new HashMap<>(Map.of("name", "Alex", "phone", "202-555-0100",
                "email", "alex@example.com", "congregation", "North", "privilege", "PUBLISHER"));
        boolean hasDepartment = false;
        for (int i = 0; i < values.length; i += 2) {
            input.put(values[i], values[i + 1]);
            hasDepartment |= values[i].startsWith("departmentRole_");
        }
        if (!hasDepartment) input.put("departmentRole_" + department.id, "VOLUNTEER");
        return input;
    }
    private Event event(String name, String type) { return service.saveEvent(null, eventInput(name, type)); }
    private Map<String, String> eventInput(String name, String type) {
        return new HashMap<>(Map.of("name", name, "type", type, "startDate", "2030-06-01", "endDate", "2030-06-03",
                "assembly", "with Circuit Overseer"));
    }
    private Map<String, String> shiftInput(String start, String end) {
        return new HashMap<>(Map.of("name", "Morning shift", "departmentId", department.id.toString(), "date", "2030-06-01",
                "startTime", start, "endTime", end, "requiredCount", "1"));
    }
}
