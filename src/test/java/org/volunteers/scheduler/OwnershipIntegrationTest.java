package org.volunteers.scheduler;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.repository.*;
import org.volunteers.scheduler.service.*;
import org.volunteers.scheduler.bootstrap.EventAdministratorBackfill;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ownership-tests;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class OwnershipIntegrationTest {
    @Autowired SchedulerService scheduler;
    @Autowired PhoneAccess access;
    @Autowired Volunteers volunteers;
    @Autowired BackupService backups;
    @Autowired EventAdministratorBackfill migration;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    List<Long> events = new ArrayList<>();
    Event event;
    @BeforeEach void setup() { event = createEvent(); }
    @AfterEach void cleanup() {
        for (Long id : events) if (scheduler.events().stream().anyMatch(e -> e.getId().equals(id))) scheduler.deleteEvent(id);
    }
    Event createEvent() {
        Event result = scheduler.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-04"));
        events.add(result.getId()); return result;
    }
    MockHttpSession session(String phone) {
        var result = new MockHttpSession(); result.setAttribute(PhoneAccess.SESSION, phone); return result;
    }
    Map<String,String> profile(String phone, boolean admin) {
        return new HashMap<>(Map.of("name", "Member " + phone, "phone", phone, "congregation", "Central", "privilege", "PUBLISHER",
            "departmentChoice", "none", "eventAdministrator", Boolean.toString(admin)));
    }
    Volunteer owner() { return volunteers.findByEventIdOrderByNameAscIdAsc(event.getId()).stream().filter(Volunteer::isEventAdministrator).findFirst().orElseThrow(); }
    @Test void ownerHasGlobalAccessWithoutAnyMembershipAndCanLogin() throws Exception {
        scheduler.deleteEvent(event.getId());
        assertThat(access.signedIn(session("8135160335"))).isTrue();
        mvc.perform(post("/login").with(csrf()).param("phone", "(813)516-0335")).andExpect(redirectedUrl("/events"));
        Event other = createEvent();
        assertThat(access.canManage(session("8135160335"), other.getId())).isTrue();
        mvc.perform(post("/events/" + other.getId() + "/delete").session(session("8135160335")).with(csrf()))
            .andExpect(redirectedUrl("/events"));
    }
    @Test void finalAdministratorCannotBeRemovedOrDemotedEvenByOwner() throws Exception {
        Volunteer owner = owner();
        assertThatThrownBy(() -> scheduler.deleteVolunteer(event.getId(), owner.getId())).hasMessage(SchedulerService.LAST_ADMIN);
        var input = profile("8135160335", false); input.remove("departmentChoice");
        var d = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking")); input.put("departmentRole_" + d.getId(), "VOLUNTEER");
        assertThatThrownBy(() -> scheduler.saveVolunteer(event.getId(), owner.getId(), input)).hasMessage(SchedulerService.LAST_ADMIN);
        mvc.perform(post("/events/" + event.getId() + "/volunteers/" + owner.getId() + "/delete").with(csrf()).session(session("8135160335")))
            .andExpect(flash().attribute("error", SchedulerService.LAST_ADMIN));
        assertThat(owner().getId()).isEqualTo(owner.getId());
    }
    @Test void transferAllowsRemovalAndDepartmentDeletionDoesNotRemoveAdmin() {
        Volunteer first = owner();
        Volunteer next = scheduler.saveVolunteer(event.getId(), null, profile("2105550001", true));
        scheduler.deleteVolunteer(event.getId(), first.getId());
        var d = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = profile("2105550001", true); input.remove("departmentChoice"); input.put("departmentRole_" + d.getId(), "VOLUNTEER");
        scheduler.saveVolunteer(event.getId(), next.getId(), input);
        scheduler.deleteDepartment(event.getId(), d.getId());
        assertThat(access.canAdminister(session("2105550001"), event.getId())).isTrue();
        assertThat(access.canManage(session("2105550001"), createEvent().getId())).isFalse();
    }
    @Test void organizersCannotEscalateOrDeleteEventsOrAdministrators() throws Exception {
        var d = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = profile("2105550002", false); input.remove("departmentChoice"); input.put("departmentRole_" + d.getId(), "OVERSEER");
        Volunteer organizer = scheduler.saveVolunteer(event.getId(), null, input);
        var session = session("2105550002");
        assertThat(access.canManage(session, event.getId())).isTrue();
        assertThat(access.canManage(session, createEvent().getId())).isFalse();
        mvc.perform(post("/events/" + event.getId() + "/delete").with(csrf()).session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/events/" + event.getId() + "/backups/restore").with(csrf()).session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/events/" + event.getId() + "/volunteers/" + owner().getId() + "/delete").with(csrf()).session(session)).andExpect(status().isForbidden());
        input.put("eventAdministrator", "true");
        mvc.perform(post("/events/" + event.getId() + "/volunteers/save").with(csrf()).session(session)
            .param("id", organizer.getId().toString()).params(new org.springframework.util.LinkedMultiValueMap<>(input.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()))))))
            .andExpect(status().isForbidden());
    }
    @Test void newEventBelongsToItsCreatorWithoutAdministrationDepartment() {
        var d = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = profile("2105550003", false); input.remove("departmentChoice"); input.put("departmentRole_" + d.getId(), "OVERSEER");
        scheduler.saveVolunteer(event.getId(), null, input);
        Event created = access.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2027-06-11"), session("2105550003"));
        events.add(created.getId());
        assertThat(scheduler.snapshot(created.getId()).departments()).isEmpty();
        assertThat(scheduler.snapshot(created.getId()).volunteers()).singleElement().satisfies(v -> {
            assertThat(v.isEventAdministrator()).isTrue(); assertThat(PhoneAccess.normalize(v.getPhone())).isEqualTo("2105550003");
        });
    }
    @Test void migrationConvertsLegacyRolesAndRepairsOrphansOnlyOnce() {
        Volunteer admin = owner();
        var d = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        jdbc.update("update volunteer set event_administrator = false where id = ?", admin.getId());
        jdbc.update("insert into volunteer_department_roles (volunteer_id, department_id, department_role) values (?, ?, 'ADMIN')", admin.getId(), d.getId());
        Event orphan = createEvent();
        jdbc.update("delete from volunteer where event_id = ?", orphan.getId());
        migration.run(null); migration.run(null);
        assertThat(owner().getDepartmentRoles().values()).containsExactly(Volunteer.Role.VOLUNTEER);
        assertThat(scheduler.snapshot(orphan.getId()).volunteers()).singleElement().satisfies(v -> assertThat(v.isEventAdministrator()).isTrue());
    }
    @Test void backupPreservesAdministratorsAndRejectsOrphanedRestore() {
        var snapshot = backups.capture(event.getId());
        backups.restore(event.getId(), snapshot.files());
        assertThat(owner()).isNotNull();
        var files = new HashMap<>(snapshot.files());
        String csv = new String(files.get("volunteers"), java.nio.charset.StandardCharsets.UTF_8);
        files.put("volunteers", csv.replace("\"true\"", "\"false\"").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> backups.restore(event.getId(), files)).hasMessage(SchedulerService.LAST_ADMIN);
        assertThat(owner()).isNotNull();
    }
    @Test void eventAdministratorsCanDeleteOnlyTheirOwnEvents() throws Exception {
        scheduler.saveVolunteer(event.getId(), null, profile("2105550005", true));
        Event other = createEvent();
        var session = session("2105550005");
        mvc.perform(post("/events/" + other.getId() + "/delete").session(session).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/events/" + event.getId() + "/delete").session(session).with(csrf())).andExpect(redirectedUrl("/events"));
    }
    @Test void administratorCanBeDemotedAfterReplacementIsAppointed() {
        Volunteer first = owner();
        scheduler.saveVolunteer(event.getId(), null, profile("2105550006", true));
        var d = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = profile("8135160335", false); input.remove("departmentChoice"); input.put("departmentRole_" + d.getId(), "VOLUNTEER");
        scheduler.saveVolunteer(event.getId(), first.getId(), input);
        assertThat(scheduler.volunteer(event.getId(), first.getId()).isEventAdministrator()).isFalse();
        assertThat(access.isAppOwner(session("8135160335"))).isTrue();
    }
    @Test void legacyBackupsConvertDepartmentAdminsAndEmptyRestoresAreRejected() {
        Volunteer first = owner();
        var d = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = profile("8135160335", true); input.remove("departmentChoice"); input.put("departmentRole_" + d.getId(), "VOLUNTEER");
        scheduler.saveVolunteer(event.getId(), first.getId(), input);
        var files = new HashMap<>(backups.capture(event.getId()).files());
        files.replaceAll((type, bytes) -> {
            String csv = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("\"scheduler-backup\",\"2\"", "\"scheduler-backup\",\"1\"");
            if (type.equals("volunteers")) {
                var lines = new ArrayList<>(csv.lines().toList());
                lines.set(1, lines.get(1).replace(",\"eventAdministrator\"", ""));
                for (int i = 2; i < lines.size(); i++) lines.set(i, lines.get(i).substring(0, lines.get(i).lastIndexOf(',')).replace("VOLUNTEER", "ADMIN"));
                csv = String.join("\r\n", lines) + "\r\n";
            }
            return csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        });
        backups.restore(event.getId(), files);
        assertThat(owner().getDepartmentRoles().values()).containsExactly(Volunteer.Role.VOLUNTEER);
        var empty = new HashMap<>(backups.capture(event.getId()).files());
        String csv = new String(empty.get("volunteers"), java.nio.charset.StandardCharsets.UTF_8);
        empty.put("volunteers", (String.join("\r\n", csv.lines().limit(2).toList()) + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> backups.restore(event.getId(), empty)).hasMessage(SchedulerService.LAST_ADMIN);
        assertThat(owner()).isNotNull();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Volunteer.Role.class)
    void logsAndNotificationManagementFollowSelectedEventRole(Volunteer.Role role) throws Exception {
        var department = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = profile("2105550010", role == Volunteer.Role.ADMIN);
        input.remove("departmentChoice");
        input.put("departmentRole_" + department.getId(), role == Volunteer.Role.ADMIN ? "VOLUNTEER" : role.name());
        scheduler.saveVolunteer(event.getId(), null, input);
        var session = session("2105550010"); session.setAttribute("activeEvent", event.getId());
        boolean allowed = Set.of(Volunteer.Role.ADMIN, Volunteer.Role.OVERSEER, Volunteer.Role.ASSISTANT_OVERSEER).contains(role);
        String settings = mvc.perform(get("/settings").session(session)).andExpect(status().isOk())
            .andExpect(model().attribute("canManageLogsAndNotifications", allowed)).andReturn().getResponse().getContentAsString();
        String more = mvc.perform(get("/events/" + event.getId() + "/more").session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(settings.contains("id=\"notification\"")).isEqualTo(allowed);
        assertThat(settings.contains("href=\"/logs\"")).isEqualTo(allowed);
        assertThat(more.contains("href=\"/logs\"")).isEqualTo(allowed);
        mvc.perform(get("/logs").session(session)).andExpect(status().is(allowed ? 200 : 403));
        // An old unlocked session must not bypass the role restriction.
        session.setAttribute("notificationUnlocked", true);
        mvc.perform(post("/settings/notifications").session(session).with(csrf()).param("message", " "))
            .andExpect(status().is(allowed ? 302 : 403));
        // Receiving and acknowledging notifications remains available to every role.
        mvc.perform(get("/api/notifications/count").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/notifications").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/notifications/read").session(session).with(csrf()).contentType("application/json").content("[]"))
            .andExpect(status().isNoContent());
    }
    @Test void switchingEventsRevokesToolAccessDespiteAnUnlockedNotificationSession() throws Exception {
        var department = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var input = profile("2105550011", false); input.remove("departmentChoice"); input.put("departmentRole_" + department.getId(), "OVERSEER");
        scheduler.saveVolunteer(event.getId(), null, input);
        var session = session("2105550011");
        // With no selected event, a qualifying role in any event is sufficient.
        mvc.perform(get("/settings").session(session)).andExpect(model().attribute("canManageLogsAndNotifications", true));
        mvc.perform(get("/logs").session(session)).andExpect(status().isOk());
        session.setAttribute("activeEvent", event.getId()); session.setAttribute("notificationUnlocked", true);
        Event other = createEvent();
        var otherDepartment = scheduler.saveDepartment(other.getId(), null, Map.of("name", "Cleaning"));
        var otherInput = profile("2105550011", false); otherInput.remove("departmentChoice"); otherInput.put("departmentRole_" + otherDepartment.getId(), "CAPTAIN");
        scheduler.saveVolunteer(other.getId(), null, otherInput);
        mvc.perform(post("/events/" + other.getId() + "/select").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/settings").session(session)).andExpect(model().attribute("canManageLogsAndNotifications", false))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"notification\""))));
        mvc.perform(get("/logs").session(session)).andExpect(status().isForbidden());
        for (String suffix : List.of("")) {
            mvc.perform(post("/settings/notifications" + suffix).session(session).with(csrf()).param("message", "Blocked").param("password", "snow"))
                .andExpect(status().isForbidden());
        }
        mvc.perform(post("/events/" + event.getId() + "/select").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/settings").session(session)).andExpect(model().attribute("canManageLogsAndNotifications", true));
        mvc.perform(get("/logs").session(session)).andExpect(status().isOk());
    }
    @Test void ownerHasToolAccessWithoutMembershipOrSelectedEvent() throws Exception {
        scheduler.deleteEvent(event.getId());
        var session = session("8135160335");
        mvc.perform(get("/settings").session(session)).andExpect(status().isOk())
            .andExpect(model().attribute("canManageLogsAndNotifications", true))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"notification\"")));
        mvc.perform(get("/logs").session(session)).andExpect(status().isOk());
        mvc.perform(post("/settings/notifications").session(session).with(csrf()).param("message", "Owner announcement"))
            .andExpect(redirectedUrl("/settings#notification"));

    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void concurrentAdministratorRemovalsRetainOneAdministrator(boolean delete) throws Exception {
        Volunteer first = owner();
        Volunteer second = scheduler.saveVolunteer(event.getId(), null, profile("2105550004", true));
        var department = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Parking"));
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Long id : List.of(first.getId(), second.getId())) results.add(executor.submit(() -> {
                ready.countDown(); start.await();
                try {
                    if (delete) scheduler.deleteVolunteer(event.getId(), id);
                    else {
                        var input = profile(id.equals(first.getId()) ? "8135160335" : "2105550004", false);
                        input.remove("departmentChoice"); input.put("departmentRole_" + department.getId(), "VOLUNTEER");
                        scheduler.saveVolunteer(event.getId(), id, input);
                    }
                    return true;
                }
                catch (IllegalArgumentException expected) { assertThat(expected).hasMessage(SchedulerService.LAST_ADMIN); return false; }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(volunteers.findByEventIdOrderByNameAscIdAsc(event.getId())).filteredOn(Volunteer::isEventAdministrator).hasSize(1);
    }
}
