package org.volunteers.scheduler;

import org.volunteers.scheduler.model.Department;
import org.volunteers.scheduler.model.Event;
import org.volunteers.scheduler.model.Shift;
import org.volunteers.scheduler.model.Volunteer;
import org.volunteers.scheduler.service.SchedulerService;
import org.volunteers.scheduler.bootstrap.TestVolunteerData;
import org.volunteers.scheduler.repository.Events;
import org.volunteers.scheduler.repository.Volunteers;
import org.volunteers.scheduler.repository.Departments;
import org.volunteers.scheduler.repository.Shifts;
import org.volunteers.scheduler.repository.Assignments;
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
class SchedulerIntegrationTest extends ManagerAccessFixture {
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
        department = service.saveDepartment(event.getId(), null, Map.of("name", "Attendants", "description", "Welcome team"));
        volunteer = service.saveVolunteer(event.getId(), null, volunteerInput("name", "Alex Rivera", "congregation", "North", "email", "alex@example.com"));
        shift = service.saveShift(event.getId(), null, shiftInput("09:00", "10:00"));
    }

    @Test void volunteerPhoneIsFormattedAndRequiresTenDigits() {
        for (String phone : List.of("8135160335", "813-516-0335", "(813) 516-0335")) {
            assertThat(service.saveVolunteer(event.getId(), volunteer.getId(), volunteerInput("phone", phone)).getPhone())
                    .isEqualTo("(813)516-0335");
        }
        for (String phone : List.of("813516033", "18135160335", "813ABC5160335")) {
            assertThatThrownBy(() -> service.saveVolunteer(event.getId(), volunteer.getId(), volunteerInput("phone", phone)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Enter a 10-digit phone number.");
        }
    }

    @Test void eventCreationDerivesNamesDatesAndValidatesRepresentative() {
        for (String date : List.of("2026-08-31", "2026-09-01", "2026-12-31")) {
            Event created = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", date));
            assertThat(created.getName()).isEqualTo((date.equals("2026-08-31") ? "2026" : "2027") + " Regional Convention");
            assertThat(created.getEndDate()).isEqualTo(LocalDate.parse(date).plusDays(2));
        }
        for (String representative : List.of("with Circuit Overseer", "with Branch Representative")) {
            Event created = service.saveEvent(null, Map.of("type", "CIRCUIT_ASSEMBLY", "startDate", "2026-10-28", "assembly", representative));
            assertThat(created.getName()).isEqualTo("Circuit Assembly " + representative);
            assertThat(created.getEndDate()).isEqualTo(created.getStartDate());
        }
        assertThatThrownBy(() -> service.saveEvent(null, Map.of("type", "CIRCUIT_ASSEMBLY", "startDate", "2026-10-28")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void regionalConventionRunsFridayThroughSunday() {
        Event created = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2026-10-30"));
        assertThat(created.getEndDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThatThrownBy(() -> service.saveShift(created.getId(), null, Map.of(
                "departmentId", service.saveDepartment(created.getId(), null, Map.of("name", "Attendants")).getId().toString(),
                "date", "2026-11-02", "startTime", "09:00", "endTime", "10:00", "requiredCount", "1", "name", "Morning shift")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Shift date must be within the event dates.");
    }

    @Test void eventEditorOnlyAppearsWhenOpened() throws Exception {
        mvc.perform(get("/events")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-event-form"))));
        mvc.perform(get("/events?create=true")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-event-form")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"name\""))));
    }

    @Test void homeShowsWeatherWithoutSelectingAnEvent() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/events"));
        mvc.perform(get("/events/" + event.getId() + "/home"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-weather")));
    }

    @Test void rendersEveryPageWithRealDataAndCsrfFields() throws Exception {
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        String base = "/events/" + event.getId();
        for (String path : List.of("/events", "/events?edit=" + event.getId(), "/settings", base + "/home", base + "/more",
                base + "/volunteers", base + "/volunteers?edit=" + volunteer.getId(), base + "/volunteers?view=" + volunteer.getId(),
                base + "/departments", base + "/departments?edit=" + department.getId(), base + "/departments?view=" + department.getId(),
                base + "/schedule", base + "/schedule?edit=" + shift.getId(),
                base + "/reports", base + "/reports?type=volunteer", base + "/reports?type=coverage")) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Scheduler Application")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<small class=\"app-version\">"))));
        }
        mvc.perform(get(base + "/schedule")).andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Alex Rivera")));
    }

    @Test void aboutShowsApplicationNameAndVersion() throws Exception {
        mvc.perform(get("/settings")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<h3>Scheduler Application</h3>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<small class=\"app-version muted\">\u00a9 2027 v.1.67.2</small>")));
    }

    @Test void eventsAreIsolatedForReadsWritesAssignmentsAndFilters() throws Exception {
        Event other = event("Assembly", "CIRCUIT_ASSEMBLY");
        assertThatThrownBy(() -> service.volunteer(other.getId(), volunteer.getId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.assign(other.getId(), shift.getId(), volunteer.getId(), false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.saveShift(other.getId(), null, shiftInput("10:00", "11:00"))).isInstanceOf(ResponseStatusException.class);
        mvc.perform(get("/events/" + other.getId() + "/volunteers?edit=" + volunteer.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/events/" + other.getId() + "/schedule?departmentId=" + department.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/events/" + other.getId() + "/reports.csv?departmentId=" + department.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/events/" + other.getId() + "/volunteers")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Alex Rivera"))));
    }

    @Test void departmentFilterCombinesWithVolunteerSearch() throws Exception {
        Department parking = service.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        service.saveVolunteer(event.getId(), null, volunteerInput("name", "Parking Person",
                "departmentRole_" + parking.getId(), "VOLUNTEER"));
        mvc.perform(get("/events/" + event.getId() + "/volunteers").param("departmentId", department.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Alex Rivera")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Parking Person"))));
        mvc.perform(get("/events/" + event.getId() + "/volunteers").param("departmentId", parking.getId().toString()).param("q", "Rivera"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No volunteers to show")));
    }

    @Test void assignmentDropdownOnlyContainsDepartmentMembersWithoutOverlaps() throws Exception {
        Department parking = service.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        Volunteer other = service.saveVolunteer(event.getId(), null, volunteerInput("name", "Parking Person",
                "departmentRole_" + parking.getId(), "VOLUNTEER"));
        Volunteer available = service.saveVolunteer(event.getId(), null, volunteerInput("name", "Available Person"));
        var busyInput = shiftInput("09:30", "10:30");
        busyInput.put("departmentId", parking.getId().toString());
        Shift busy = service.saveShift(event.getId(), null, busyInput);
        service.assign(event.getId(), busy.getId(), volunteer.getId(), false);
        Shift adjacent = service.saveShift(event.getId(), null, shiftInput("10:30", "11:00"));
        var data = service.snapshot(event.getId());
        assertThat(data.availableVolunteers(shift)).extracting(v -> v.getId()).containsExactly(available.getId());
        assertThat(data.availableVolunteers(adjacent)).extracting(v -> v.getId())
                .containsExactlyInAnyOrder(volunteer.getId(), available.getId()).doesNotContain(other.getId());
        String html = mvc.perform(get("/events/" + event.getId() + "/schedule"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String card = html.substring(html.indexOf("id=\"shift-" + shift.getId() + "\""));
        card = card.substring(0, card.indexOf("</article>"));
        assertThat(card).contains("Available Person").doesNotContain("Alex Rivera", "Parking Person");
        service.assign(event.getId(), shift.getId(), available.getId(), false);
        assertThat(service.snapshot(event.getId()).availableVolunteers(shift)).isEmpty();
        html = mvc.perform(get("/events/" + event.getId() + "/schedule"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        card = html.substring(html.indexOf("id=\"shift-" + shift.getId() + "\""));
        card = card.substring(0, card.indexOf("</article>"));
        assertThat(card).contains("Choose Volunteers...", "checked=\"checked\"")
                .doesNotContain("No available volunteers in this department");
    }

    @Test void overlapRequiresConfirmationButAdjacentAndOverstaffedShiftsAreAllowed() {
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        Shift overlapping = service.saveShift(event.getId(), null, shiftInput("09:30", "10:30"));
        assertThatThrownBy(() -> service.assign(event.getId(), overlapping.getId(), volunteer.getId(), false))
                .isInstanceOf(SchedulerService.OverlapException.class).hasMessageContaining("Attendants");
        service.assign(event.getId(), overlapping.getId(), volunteer.getId(), true);
        Shift adjacent = service.saveShift(event.getId(), null, shiftInput("10:30", "11:00"));
        service.assign(event.getId(), adjacent.getId(), volunteer.getId(), false);
        assertThatThrownBy(() -> service.assign(event.getId(), shift.getId(), volunteer.getId(), true)).hasMessageContaining("already assigned");
        Volunteer second = service.saveVolunteer(event.getId(), null, volunteerInput("name", "Jamie"));
        service.assign(event.getId(), shift.getId(), second.getId(), false);
        var row = service.snapshot(event.getId()).rows().stream().filter(r -> r.shift().getId().equals(shift.getId())).findFirst().orElseThrow();
        assertThat(row.getStatus()).isEqualTo("Overfilled"); assertThat(row.getMissing()).isZero();
    }

    @Test void changingAssignedShiftChecksConflictsAndRetainsOriginalOnFailure() {
        Shift later = service.saveShift(event.getId(), null, shiftInput("10:00", "11:00"));
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false); service.assign(event.getId(), later.getId(), volunteer.getId(), false);
        var input = shiftInput("09:30", "10:30");
        assertThatThrownBy(() -> service.saveShift(event.getId(), later.getId(), input)).isInstanceOf(SchedulerService.OverlapException.class);
        assertThat(service.shift(event.getId(), later.getId()).getStartTime()).isEqualTo(LocalTime.of(10, 0));
        input.put("confirmOverlap", "true"); service.saveShift(event.getId(), later.getId(), input);
        assertThat(service.shift(event.getId(), later.getId()).getStartTime()).isEqualTo(LocalTime.of(9, 30));
    }

    @Test void assemblyShiftsUseEventDateAndRequireFifteenMinuteTimes() throws Exception {
        Event assembly = event("Assembly", "CIRCUIT_ASSEMBLY");
        Department assemblyDepartment = service.saveDepartment(assembly.getId(), null, Map.of("name", "Attendants"));
        var input = shiftInput("06:00", "17:30");
        input.put("departmentId", assemblyDepartment.getId().toString());
        input.remove("date");
        Shift created = service.saveShift(assembly.getId(), null, input);
        assertThat(created.getDate()).isEqualTo(assembly.getStartDate());
        input.put("date", "2031-01-01");
        assertThat(service.saveShift(assembly.getId(), created.getId(), input).getDate()).isEqualTo(assembly.getStartDate());
        for (String invalid : List.of("05:45", "17:45", "06:05", "06:10", "06:00:01", "06:00:00.001", "")) {
            input.put("startTime", invalid);
            assertThatThrownBy(() -> service.saveShift(assembly.getId(), null, input)).isInstanceOf(IllegalArgumentException.class);
        }
        input.put("startTime", "06:00");
        for (String invalid : List.of("17:45", "17:25", "17:20", "17:30:01", "17:15:00.001", "", "06:00")) {
            input.put("endTime", invalid);
            assertThatThrownBy(() -> service.saveShift(assembly.getId(), null, input)).isInstanceOf(IllegalArgumentException.class);
        }
        String html = mvc.perform(get("/events/" + assembly.getId() + "/schedule?create=true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String editor = html.substring(html.indexOf("id=\"editor\""));
        assertThat(editor).doesNotContain("name=\"date\"")
                .contains("name=\"startTime\" required", "name=\"endTime\" required", "06:00 AM", "06:15 AM", "06:45 AM", "05:30 PM")
                .doesNotContain("05:40 PM", "06:05 AM");
    }

    @Test void assemblyShiftsAcceptEveryDropdownTimeForCreationAndEditing() throws Exception {
        Event assembly = event("Assembly", "CIRCUIT_ASSEMBLY");
        Department assemblyDepartment = service.saveDepartment(assembly.getId(), null, Map.of("name", "Attendants"));
        var result = mvc.perform(get("/events/" + assembly.getId() + "/schedule?create=true"))
                .andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked")
        List<LocalTime> times = (List<LocalTime>) result.getModelAndView().getModel().get("shiftTimes");
        assertThat(times).hasSize(47).contains(LocalTime.of(6, 15), LocalTime.of(6, 45));
        for (LocalTime time : times) {
            var input = shiftInput(time.equals(LocalTime.of(17, 30)) ? "06:00" : time.toString(),
                    time.equals(LocalTime.of(17, 30)) ? time.toString() : "17:30");
            input.put("departmentId", assemblyDepartment.getId().toString());
            Shift created = service.saveShift(assembly.getId(), null, input);
            assertThat(created.getStartTime()).isEqualTo(LocalTime.parse(input.get("startTime")));
            if (time.isAfter(LocalTime.of(6, 0))) {
                input.put("startTime", "06:00");
                input.put("endTime", time.toString());
                Shift updated = service.saveShift(assembly.getId(), created.getId(), input);
                assertThat(updated.getEndTime()).isEqualTo(time);
            }
        }
        var reversed = shiftInput("06:45", "06:15");
        reversed.put("departmentId", assemblyDepartment.getId().toString());
        assertThatThrownBy(() -> service.saveShift(assembly.getId(), null, reversed))
                .hasMessageContaining("End time must be after start time");
    }

    @Test void shiftNamesAreRequiredSavedAndEditable() throws Exception {
        assertThat(service.shift(event.getId(), shift.getId()).getName()).isEqualTo("Morning shift");
        var input = shiftInput("09:00", "10:00");
        input.put("name", "  Afternoon welcome  ");
        service.saveShift(event.getId(), shift.getId(), input);
        assertThat(service.shift(event.getId(), shift.getId()).getName()).isEqualTo("Afternoon welcome");
        mvc.perform(get("/events/" + event.getId() + "/schedule?edit=" + shift.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Afternoon welcome")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"name\"")));
        for (String invalid : List.of("", "   ", "x".repeat(161))) {
            input.put("name", invalid);
            assertThatThrownBy(() -> service.saveShift(event.getId(), shift.getId(), input)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.shift(event.getId(), shift.getId()).getName()).isEqualTo("Afternoon welcome");
    }

    @Test void validatesDatesHeadcountsAndEmail() {
        var input = shiftInput("11:00", "10:00");
        assertThatThrownBy(() -> service.saveShift(event.getId(), null, input)).hasMessageContaining("End time");
        input.putAll(Map.of("endTime", "12:00", "date", "2031-01-01"));
        assertThatThrownBy(() -> service.saveShift(event.getId(), null, input)).hasMessageContaining("event dates");
        input.putAll(Map.of("date", "2030-06-01", "requiredCount", "0"));
        assertThatThrownBy(() -> service.saveShift(event.getId(), null, input)).hasMessageContaining("Required volunteers");
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), null, volunteerInput("name", "Alex", "email", "bad"))).hasMessageContaining("email");
        var eventInput = eventInput("Regional", "REGIONAL_CONVENTION"); eventInput.put("startDate", "2030-06-02");
        assertThatThrownBy(() -> service.saveEvent(event.getId(), eventInput)).hasMessageContaining("existing shifts");
    }

    @Test void deletionRemovesDependentsAndPreservesOtherEvents() {
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        Event other = event("Assembly", "CIRCUIT_ASSEMBLY");
        service.deleteVolunteer(event.getId(), volunteer.getId());
        assertThat(service.snapshot(event.getId()).assignments()).isEmpty();
        assertThat(service.snapshot(event.getId()).shifts()).hasSize(1);
        service.deleteDepartment(event.getId(), department.getId());
        assertThat(service.snapshot(event.getId()).shifts()).isEmpty();
        assertThat(service.snapshot(event.getId()).departments()).isEmpty();
        service.deleteEvent(event.getId());
        events.flush();
        assertThat(service.event(other.getId()).getType()).isEqualTo(Event.Type.CIRCUIT_ASSEMBLY);
        assertThatThrownBy(() -> service.event(event.getId())).isInstanceOf(ResponseStatusException.class);
    }

    @Test void deletingPopulatedEventCleansAllDependents() {
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        service.deleteEvent(event.getId()); events.flush();
        assertThat(assignments.count()).isZero(); assertThat(shifts.count()).isZero();
        assertThat(departments.count()).isZero(); assertThat(volunteers.count()).isZero(); assertThat(events.count()).isZero();
    }

    @Test void browserFlowPreservesValidationInputAndRequiresCsrf() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/").session(session)).andExpect(redirectedUrl("/events"));
        mvc.perform(post("/events/save").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/events/" + event.getId() + "/select").session(session).with(csrf()))
                .andExpect(redirectedUrl("/events/" + event.getId() + "/home"));
        mvc.perform(get("/").session(session)).andExpect(redirectedUrl("/events/" + event.getId() + "/home"));
        var result = mvc.perform(post("/events/" + event.getId() + "/volunteers/save").with(csrf())
                .param("name", "Keep my input").param("email", "bad-email"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error", "draft")).andReturn();
        mvc.perform(get("/events/" + event.getId() + "/volunteers").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Keep my input")));
    }

    @Test void overlapConfirmationPageRendersAndExplicitPostWorks() throws Exception {
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        Shift overlapping = service.saveShift(event.getId(), null, shiftInput("09:30", "10:30"));
        String url = "/events/" + event.getId() + "/schedule/" + overlapping.getId() + "/assign";
        var result = mvc.perform(post(url).with(csrf()).param("volunteerId", volunteer.getId().toString()))
                .andExpect(flash().attributeExists("pending", "error")).andReturn();
        mvc.perform(get("/events/" + event.getId() + "/schedule").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Confirm overlapping assignment")));
        mvc.perform(post(url).with(csrf()).param("volunteerId", volunteer.getId().toString()).param("confirmOverlap", "true"))
                .andExpect(flash().attribute("notice", "Volunteer assigned."));
        assertThat(service.snapshot(event.getId()).assignments()).hasSize(2);
    }

    @Test void reportsFilterAndCsvEscapesUntrustedValues() throws Exception {
        service.saveVolunteer(event.getId(), volunteer.getId(), volunteerInput("name", "=SUM(1,2)\"é"));
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        String base = "/events/" + event.getId();
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
        mvc.perform(get("/events/" + event.getId() + "/schedule").cookie(new jakarta.servlet.http.Cookie("schedulerTimeFormat", "24")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("09:00")));
    }

    @Test void volunteerDepartmentsRolesAndPrivilegePersistAndRender() throws Exception {
        Department second = service.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        String firstKey = "departmentRole_" + department.getId();
        String secondKey = "departmentRole_" + second.getId();
        mvc.perform(post("/events/" + event.getId() + "/volunteers/save").with(csrf())
                .param("id", volunteer.getId().toString()).param("name", "Alex Rivera")
                .param("phone", "202-555-0100").param("email", "alex@example.com").param("congregation", "North")
                .param("privilege", "ELDER").param(firstKey, "OVERSEER").param(secondKey, "VOLUNTEER"))
                .andExpect(flash().attribute("notice", "Volunteer saved."));
        entityManager.flush(); entityManager.clear();
        Volunteer saved = service.volunteer(event.getId(), volunteer.getId());
        assertThat(saved.getPrivilege()).isEqualTo(Volunteer.Privilege.ELDER);
        assertThat(saved.getDepartmentRoles().values()).containsExactlyInAnyOrder(Volunteer.Role.OVERSEER, Volunteer.Role.VOLUNTEER);
        mvc.perform(get("/events/" + event.getId() + "/volunteers?edit=" + volunteer.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Attendants Overseer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Parking Volunteer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"ELDER\" selected")));
        service.saveVolunteer(event.getId(), volunteer.getId(), volunteerInput("name", "Alex", "privilege", "PUBLISHER", secondKey, "ADMIN"));
        entityManager.flush(); entityManager.clear();
        assertThat(service.volunteer(event.getId(), volunteer.getId()).getDepartmentRoles().values()).containsExactly(Volunteer.Role.ADMIN);
        service.deleteDepartment(event.getId(), second.getId());
        entityManager.flush(); entityManager.clear();
        assertThat(service.volunteer(event.getId(), volunteer.getId()).getDepartmentRoles()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Volunteer.Role.class, names = {"CAPTAIN", "KEY_MAN"})
    void captainAndKeyManCanBeCreatedEditedAndRendered(Volunteer.Role role) throws Exception {
        String base = "/events/" + event.getId() + "/volunteers";
        String key = "departmentRole_" + department.getId();
        var create = post(base + "/save").with(csrf());
        volunteerInput("name", "New Role Volunteer", key, role.name()).forEach(create::param);
        mvc.perform(create).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", "Volunteer saved."));
        entityManager.flush(); entityManager.clear();
        Volunteer created = service.snapshot(event.getId()).volunteers().stream()
                .filter(v -> "New Role Volunteer".equals(v.getName())).findFirst().orElseThrow();
        assertThat(created.getDepartmentRoles().values()).containsExactly(role);

        var edit = post(base + "/save").with(csrf()).param("id", volunteer.getId().toString());
        volunteerInput("name", "Edited Role Volunteer", key, role.name()).forEach(edit::param);
        mvc.perform(edit).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", "Volunteer saved."));
        entityManager.flush(); entityManager.clear();
        assertThat(service.volunteer(event.getId(), volunteer.getId()).getDepartmentRoles().values())
                .containsExactly(role);
        for (Long id : List.of(created.getId(), volunteer.getId())) {
            mvc.perform(get(base).param("edit", id.toString()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Attendants " + role.getLabel())))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"" + role.name() + "\" selected")));
        }
    }

    @Test void volunteerRolesValidateAndPreserveFailedFormInput() throws Exception {
        String key = "departmentRole_" + department.getId();
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), null, volunteerInput("name", "Alex", key, "UNKNOWN")))
                .hasMessageContaining("valid role");
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), null, volunteerInput("name", "Alex", "privilege", "UNKNOWN")))
                .hasMessageContaining("valid privilege");
        Event other = event("Other", "CIRCUIT_ASSEMBLY");
        assertThatThrownBy(() -> service.saveVolunteer(other.getId(), null, volunteerInput("name", "Alex", key, "ADMIN")))
                .isInstanceOf(ResponseStatusException.class);
        var result = mvc.perform(post("/events/" + event.getId() + "/volunteers/save").with(csrf())
                .param("name", "Alex").param("email", "invalid").param("privilege", "MINISTERIAL_SERVANT")
                .param(key, "ASSISTANT_OVERSEER"))
                .andExpect(flash().attributeExists("error", "draft")).andReturn();
        mvc.perform(get("/events/" + event.getId() + "/volunteers").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"ASSISTANT_OVERSEER\" selected")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"MINISTERIAL_SERVANT\" selected")));
    }

    @Test void volunteerIdentityAndMembershipFieldsAreRequired() {
        for (String key : List.of("name", "phone", "congregation", "privilege", "departmentRole_" + department.getId())) {
            var input = volunteerInput();
            input.put(key, "   ");
            assertThatThrownBy(() -> service.saveVolunteer(event.getId(), null, input))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.saveVolunteer(event.getId(), null, volunteerInput()).getNotes()).isEmpty();
    }

    @Test void leadershipLimitsApplyToCreationAndEditingPerDepartment() {
        String key = "departmentRole_" + department.getId();
        service.saveVolunteer(event.getId(), volunteer.getId(), volunteerInput(key, "OVERSEER"));
        service.saveVolunteer(event.getId(), volunteer.getId(), volunteerInput(key, "OVERSEER"));
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), null, volunteerInput(key, "OVERSEER")))
                .hasMessageContaining("at most 1 Overseer");
        service.saveVolunteer(event.getId(), null, volunteerInput(key, "ASSISTANT_OVERSEER"));
        service.saveVolunteer(event.getId(), null, volunteerInput(key, "ASSISTANT_OVERSEER"));
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), null, volunteerInput(key, "ASSISTANT_OVERSEER")))
                .hasMessageContaining("at most 2 Assistant Overseers");
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), volunteer.getId(), volunteerInput(key, "ASSISTANT_OVERSEER")))
                .hasMessageContaining("at most 2 Assistant Overseers");
        assertThat(service.volunteer(event.getId(), volunteer.getId()).getDepartmentRoles().values()).containsExactly(Volunteer.Role.OVERSEER);
        Department other = service.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        service.saveVolunteer(event.getId(), null, volunteerInput("departmentRole_" + other.getId(), "OVERSEER"));
    }

    @Test void testDataHasRequestedMembershipsAndIsRepeatSafe() {
        TestVolunteerData seed = new TestVolunteerData(service);
        seed.run();
        seed.run();
        Event seeded = service.events().stream().filter(e -> "Volunteer Test Event".equals(e.getName())).findFirst().orElseThrow();
        var data = service.snapshot(seeded.getId());
        assertThat(data.volunteers()).hasSize(100);
        assertThat(data.volunteers().stream().filter(v -> v.getDepartmentRoles().size() > 1).count()).isEqualTo(10);
        for (Department d : data.departments()) {
            assertThat(data.volunteers().stream().filter(v -> v.getDepartmentRoles().get(d) == Volunteer.Role.OVERSEER).count()).isEqualTo(1);
            assertThat(data.volunteers().stream().filter(v -> v.getDepartmentRoles().get(d) == Volunteer.Role.ASSISTANT_OVERSEER).count()).isEqualTo(2);
            assertThat(data.volunteers().stream().filter(v -> v.getDepartmentRoles().get(d) == Volunteer.Role.CAPTAIN).count()).isEqualTo(1);
            assertThat(data.volunteers().stream().filter(v -> v.getDepartmentRoles().get(d) == Volunteer.Role.KEY_MAN).count()).isEqualTo(1);
        }
        assertThat(service.snapshot(event.getId()).volunteers()).hasSize(1);
    }

    @Test void volunteerCardsKeepContactsInDialogAndShowOnlyPersonalAssignments() throws Exception {
        service.saveVolunteer(event.getId(), volunteer.getId(), volunteerInput("name", "Alex Rivera", "notes", "Private <note>"));
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        Volunteer other = service.saveVolunteer(event.getId(), null, volunteerInput("name", "Other Person", "email", "other@example.com"));
        Shift otherShift = service.saveShift(event.getId(), null, shiftInput("11:00", "12:00"));
        service.assign(event.getId(), otherShift.getId(), other.getId(), false);
        var response = mvc.perform(get("/events/" + event.getId() + "/volunteers").param("view", volunteer.getId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String card = response.substring(response.indexOf("<article class=\"card volunteer-card\""));
        card = card.substring(0, card.indexOf("</article>"));
        assertThat(card.replaceAll("\\s+", " ")).contains("data-volunteer-dialog", "Assignments →", "Edit", "Delete", "#privilege", "#departments")
                .doesNotContain("alex@example.com", "Private", "tel:", "mailto:");
        String dialog = response.substring(response.indexOf("id=\"volunteer-dialog-" + volunteer.getId() + "\""));
        dialog = dialog.substring(0, dialog.indexOf("</dialog>"));
        assertThat(dialog).contains("data-open-on-load=\"true\"", "mailto:alex@example.com", "Private &lt;note&gt;", "9:00 AM", "Close")
                .doesNotContain("Other Person", "11:00", ">Edit<", ">Delete<");
        mvc.perform(get("/events/" + event.getId() + "/volunteers").param("view", volunteer.getId().toString())
                .cookie(new jakarta.servlet.http.Cookie("schedulerTimeFormat", "24")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("09:00")));
    }

    @Test void directVolunteerDialogWorksOutsideFilterAndRejectsOtherEvent() throws Exception {
        Department parking = service.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        mvc.perform(get("/events/" + event.getId() + "/volunteers")
                .param("departmentId", parking.getId().toString()).param("view", volunteer.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-open-on-load=\"true\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No assignments yet.")));
        Event otherEvent = event("Other", "REGIONAL_CONVENTION");
        mvc.perform(get("/events/" + otherEvent.getId() + "/volunteers").param("view", volunteer.getId().toString()))
                .andExpect(status().isNotFound());
    }

    @Test void inlineAssignmentsPreserveFiltersAndDoNotOpenDialog() throws Exception {
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        String path = "/events/" + event.getId() + "/volunteers";
        var response = mvc.perform(get(path).param("assignments", volunteer.getId().toString())
                .param("departmentId", department.getId().toString()).param("q", "Alex"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("id=\"volunteer-assignments\"", "Alex Rivera’s assignments", "Morning shift",
                "assignments=" + volunteer.getId(), "departmentId=" + department.getId() + "&amp;q=Alex")
                .doesNotContain("data-open-on-load=\"true\"");
        String panel = response.substring(response.indexOf("id=\"volunteer-assignments\""));
        assertThat(panel.substring(0, panel.indexOf("Close</a>")))
                .contains("href=\"" + path + "?departmentId=" + department.getId() + "&amp;q=Alex\"");
        mvc.perform(get(path).param("view", volunteer.getId().toString()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("id=\"volunteer-assignments\""))));
    }

    @Test void inlineAssignmentsHandleEmptyAndRejectOtherEvents() throws Exception {
        String path = "/events/" + event.getId() + "/volunteers";
        var result = mvc.perform(get(path).param("assignments", volunteer.getId().toString()))
                .andExpect(status().isOk()).andReturn();
        assertThat((List<?>) result.getModelAndView().getModel().get("personalRows")).isEmpty();
        assertThat(result.getResponse().getContentAsString()).contains("No assignments yet.");
        Event other = event("Other", "REGIONAL_CONVENTION");
        mvc.perform(get("/events/" + other.getId() + "/volunteers").param("assignments", volunteer.getId().toString()))
                .andExpect(status().isNotFound());
    }

    @Test void noDepartmentOptionIsOnlyAvailableWhenCreatingVolunteers() throws Exception {
        Volunteer unassigned = service.saveVolunteer(event.getId(), null, noDepartmentInput());
        assertThat(unassigned.getDepartmentRoles()).isEmpty();
        mvc.perform(get("/events/" + event.getId() + "/volunteers").param("edit", unassigned.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("value=\"none\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No Department Yet")));
        mvc.perform(get("/events/" + event.getId() + "/volunteers").param("edit", volunteer.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("value=\"none\""))));
        mvc.perform(get("/events/" + event.getId() + "/volunteers").param("create", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"none\"")));
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), unassigned.getId(), noDepartmentInput()))
                .hasMessageContaining("Choose a department and its role.");
        var missingDepartment = noDepartmentInput();
        missingDepartment.remove("departmentChoice");
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), unassigned.getId(), missingDepartment))
                .hasMessageContaining("Choose a department and its role.");
        assertThat(unassigned.getDepartmentRoles()).isEmpty();
        var rejectedEdit = mvc.perform(post("/events/" + event.getId() + "/volunteers/save").with(csrf())
                .param("id", unassigned.getId().toString())
                .param("name", "Alex").param("phone", "202-555-0100")
                .param("email", "alex@example.com").param("congregation", "North")
                .param("privilege", "PUBLISHER").param("departmentChoice", "none"))
                .andExpect(flash().attributeExists("error", "draft")).andReturn();
        mvc.perform(get("/events/" + event.getId() + "/volunteers")
                .param("edit", unassigned.getId().toString()).flashAttrs(rejectedEdit.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("value=\"none\""))));
        service.saveVolunteer(event.getId(), unassigned.getId(), volunteerInput());
        assertThat(unassigned.getDepartmentRoles()).containsKey(department);
        var input = noDepartmentInput();
        input.remove("departmentChoice");
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), null, input)).isInstanceOf(IllegalArgumentException.class);
        input.put("departmentChoice", "none");
        input.put("departmentRole_" + department.getId(), "VOLUNTEER");
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), null, input)).hasMessageContaining("cannot be combined");
    }

    @Test void noDepartmentWorksWithoutAnyDepartmentsAndSurvivesFormErrors() throws Exception {
        Event empty = event("Empty", "REGIONAL_CONVENTION");
        Volunteer saved = service.saveVolunteer(empty.getId(), null, noDepartmentInput());
        assertThat(saved.getDepartmentRoles()).isEmpty();
        mvc.perform(get("/events/" + empty.getId() + "/volunteers").param("create", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"departmentChoice\"")));
        var result = mvc.perform(post("/events/" + event.getId() + "/volunteers/save").with(csrf())
                .param("name", "Alex").param("phone", "invalid").param("departmentChoice", "none"))
                .andExpect(flash().attributeExists("error", "draft")).andReturn();
        mvc.perform(get("/events/" + event.getId() + "/volunteers").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"none\" selected=\"selected\"")));
    }

    @Test void firstAssignmentSetsDepartmentAndRoleAndLaterAvailabilityUsesMembership() {
        Volunteer unassigned = service.saveVolunteer(event.getId(), null, noDepartmentInput());
        Department parking = service.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = shiftInput("11:00", "12:00");
        input.put("departmentId", parking.getId().toString());
        Shift parkingShift = service.saveShift(event.getId(), null, input);
        assertThat(service.snapshot(event.getId()).availableVolunteers(shift)).contains(unassigned);
        assertThat(service.snapshot(event.getId()).availableVolunteers(parkingShift)).contains(unassigned);
        service.assign(event.getId(), shift.getId(), unassigned.getId(), false);
        assertThat(unassigned.getDepartmentRoles()).containsEntry(department, Volunteer.Role.VOLUNTEER).hasSize(1);
        assertThat(service.snapshot(event.getId()).availableVolunteers(parkingShift)).doesNotContain(unassigned);
        Long assignmentId = service.snapshot(event.getId()).assignments().stream()
                .filter(a -> a.getVolunteer().getId().equals(unassigned.getId())).findFirst().orElseThrow().getId();
        service.unassign(event.getId(), assignmentId);
        assertThat(unassigned.getDepartmentRoles()).containsEntry(department, Volunteer.Role.VOLUNTEER);
        assertThat(service.snapshot(event.getId()).availableVolunteers(shift)).contains(unassigned);
    }

    @Test void rejectingDepartmentRemovalPreservesMembershipAndAssignments() {
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        assertThatThrownBy(() -> service.saveVolunteer(event.getId(), volunteer.getId(), noDepartmentInput()))
                .hasMessageContaining("Choose a department and its role.");
        assertThat(volunteer.getDepartmentRoles()).containsKey(department);
        assertThat(service.snapshot(event.getId()).assignments()).hasSize(1);
        assertThatThrownBy(() -> service.assign(event.getId(), shift.getId(), volunteer.getId(), false))
                .hasMessageContaining("already assigned");
        assertThat(volunteer.getDepartmentRoles()).containsKey(department);
        Department parking = service.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = shiftInput("09:30", "10:30");
        input.put("departmentId", parking.getId().toString());
        Shift overlap = service.saveShift(event.getId(), null, input);
        assertThat(service.snapshot(event.getId()).availableVolunteers(overlap)).doesNotContain(volunteer);
        assertThatThrownBy(() -> service.assign(event.getId(), overlap.getId(), volunteer.getId(), false))
                .isInstanceOf(SchedulerService.OverlapException.class);
        assertThat(volunteer.getDepartmentRoles()).containsKey(department);
        service.assign(event.getId(), overlap.getId(), volunteer.getId(), true);
        assertThat(volunteer.getDepartmentRoles()).containsKey(department).doesNotContainKey(parking);
        assertThat(service.snapshot(event.getId()).assignments()).hasSize(2);
    }

    private Map<String, String> noDepartmentInput() {
        var input = volunteerInput();
        input.keySet().removeIf(key -> key.startsWith("departmentRole_"));
        input.put("departmentChoice", "none");
        return input;
    }

    @Test void batchAssignmentSavesMultipleVolunteersThroughEndpoint() throws Exception {
        Volunteer second = service.saveVolunteer(event.getId(), null,
                volunteerInput("name", "Second", "phone", "2025550101", "email", "second@example.com"));
        mvc.perform(post("/events/" + event.getId() + "/schedule/" + shift.getId() + "/assign-batch")
                .with(csrf()).param("volunteerIds", volunteer.getId().toString(), second.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", "Volunteers assigned."));
        assertThat(service.snapshot(event.getId()).assignments()).hasSize(2);
    }

    @Test void batchAssignmentRejectsInvalidSelectionsBeforeSaving() {
        Volunteer unassigned = service.saveVolunteer(event.getId(), null, noDepartmentInput());
        Department other = service.saveDepartment(event.getId(), null, Map.of("name", "Other"));
        Volunteer wrongDepartment = service.saveVolunteer(event.getId(), null,
                volunteerInput("name", "Other", "phone", "2025550102", "email", "other@example.com",
                        "departmentRole_" + other.getId(), "VOLUNTEER"));
        Event otherEvent = event("Other event", "REGIONAL_CONVENTION");
        Volunteer foreign = service.saveVolunteer(otherEvent.getId(), null, noDepartmentInput());
        for (List<Long> selection : List.of(List.<Long>of(), List.of(volunteer.getId(), volunteer.getId()),
                List.of(volunteer.getId(), unassigned.getId()), List.of(volunteer.getId(), wrongDepartment.getId()),
                List.of(volunteer.getId(), foreign.getId()), List.of(volunteer.getId(), Long.MAX_VALUE))) {
            assertThatThrownBy(() -> service.assignBatch(event.getId(), shift.getId(), selection))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.snapshot(event.getId()).assignments()).isEmpty();
        }
    }

    @Test void batchAssignmentRejectsAlreadyAssignedAndOverlappingVolunteers() {
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        Shift overlapping = service.saveShift(event.getId(), null, shiftInput("09:30", "10:30"));
        for (Shift target : List.of(shift, overlapping)) {
            assertThatThrownBy(() -> service.assignBatch(event.getId(), target.getId(), List.of(volunteer.getId())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.snapshot(event.getId()).assignments()).hasSize(1);
    }

    @Test void scheduleRendersCheckboxDialogForDepartmentMembersOnly() throws Exception {
        Volunteer unassigned = service.saveVolunteer(event.getId(), null, noDepartmentInput());
        var result = mvc.perform(get("/events/" + event.getId() + "/schedule"))
                .andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Choose Volunteers...", "assignment-dialog", "name=\"volunteerIds\"",
                "data-assignment-submit>Save").doesNotContain("class=\"assignment-form\"");
        @SuppressWarnings("unchecked")
        var options = (Map<Long, List<Volunteer>>) result.getModelAndView().getModel().get("assignmentOptions");
        assertThat(options.get(shift.getId())).containsExactly(volunteer).doesNotContain(unassigned);
    }

    @Test void assignmentSelectionSupportsAddRemoveRetainAndClear() throws Exception {
        Volunteer second = service.saveVolunteer(event.getId(), null,
                volunteerInput("name", "Second", "phone", "2025550101", "email", "second@example.com"));
        service.updateAssignments(event.getId(), shift.getId(), List.of(volunteer.getId()));
        Long retainedId = service.snapshot(event.getId()).assignments().getFirst().getId();
        service.updateAssignments(event.getId(), shift.getId(), List.of(volunteer.getId(), second.getId()));
        assertThat(service.snapshot(event.getId()).assignments()).hasSize(2);
        service.updateAssignments(event.getId(), shift.getId(), List.of(volunteer.getId()));
        assertThat(service.snapshot(event.getId()).assignments()).extracting(a -> a.getId()).containsExactly(retainedId);
        String html = mvc.perform(get("/events/" + event.getId() + "/schedule"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("assigned-person", "checked=\"checked\"", "Choose Volunteers...")
                .doesNotContain("chip-remove");
        mvc.perform(post("/events/" + event.getId() + "/schedule/" + shift.getId() + "/assignments")
                .with(csrf())).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", "Volunteers updated."));
        assertThat(service.snapshot(event.getId()).assignments()).isEmpty();
    }

    @Test void invalidAssignmentSelectionPreservesExistingAssignments() {
        service.assign(event.getId(), shift.getId(), volunteer.getId(), false);
        Volunteer second = service.saveVolunteer(event.getId(), null,
                volunteerInput("name", "Second", "phone", "2025550101", "email", "second@example.com"));
        Shift overlapping = service.saveShift(event.getId(), null, shiftInput("09:30", "10:30"));
        service.assign(event.getId(), overlapping.getId(), second.getId(), false);
        Volunteer noDepartment = service.saveVolunteer(event.getId(), null, noDepartmentInput());
        for (List<Long> selection : List.of(List.of(second.getId()), List.of(Long.MAX_VALUE),
                List.of(noDepartment.getId()), List.of(volunteer.getId(), volunteer.getId()))) {
            assertThatThrownBy(() -> service.updateAssignments(event.getId(), shift.getId(), selection))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.snapshot(event.getId()).assignments()).hasSize(2);
        }
    }

    private Map<String, String> volunteerInput(String... values) {
        Map<String, String> input = new HashMap<>(Map.of("name", "Alex", "phone", "202-555-0100",
                "email", "alex@example.com", "congregation", "North", "privilege", "PUBLISHER"));
        boolean hasDepartment = false;
        for (int i = 0; i < values.length; i += 2) {
            input.put(values[i], values[i + 1]);
            hasDepartment |= values[i].startsWith("departmentRole_");
        }
        if (!hasDepartment) input.put("departmentRole_" + department.getId(), "VOLUNTEER");
        return input;
    }
    private Event event(String name, String type) { return service.saveEvent(null, eventInput(name, type)); }
    private Map<String, String> eventInput(String name, String type) {
        return new HashMap<>(Map.of("name", name, "type", type, "startDate", "2030-06-01", "endDate", "2030-06-03",
                "assembly", "with Circuit Overseer"));
    }
    private Map<String, String> shiftInput(String start, String end) {
        return new HashMap<>(Map.of("name", "Morning shift", "departmentId", department.getId().toString(), "date", "2030-06-01",
                "startTime", start, "endTime", end, "requiredCount", "1"));
    }
}
