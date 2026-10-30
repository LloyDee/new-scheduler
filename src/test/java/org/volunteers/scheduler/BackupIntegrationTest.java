package org.volunteers.scheduler;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.volunteers.scheduler.service.*;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.repository.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:backup-tests;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class BackupIntegrationTest extends ManagerAccessFixture {
    @Autowired SchedulerService scheduler;
    @Autowired BackupService backups;
    @Autowired MockMvc mvc;
    @Autowired Departments departments;
    @Autowired Volunteers volunteers;
    @Autowired Shifts shifts;
    Event event;
    Department department;
    Volunteer volunteer;
    Shift shift;
    String special = "  =SUM(1,2)\r\n\"José\" '雪";
    @BeforeEach void setup() {
        event = scheduler.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2026-10-29", "venue", "Hall"));
        department = scheduler.saveDepartment(event.getId(), null, Map.of("name", special, "description", "'original\nnotes"));
        scheduler.saveDepartment(event.getId(), null, Map.of("name", "Empty department"));
        volunteer = scheduler.saveVolunteer(event.getId(), null, Map.of("name", "Alex", "phone", "8135160335",
            "email", "alex@example.com", "congregation", "North", "privilege", "ELDER", "notes", special,
            "departmentRole_" + department.getId(), "VOLUNTEER"));
        scheduler.saveVolunteer(event.getId(), null, Map.of("name", "Alex", "phone", "8135160336",
            "email", "other@example.com", "congregation", "South", "privilege", "PUBLISHER",
            "departmentRole_" + department.getId(), "CAPTAIN"));
        shift = scheduler.saveShift(event.getId(), null, Map.of("name", "Morning", "departmentId", department.getId().toString(),
            "date", "2026-10-29", "startTime", "09:00", "endTime", "10:00", "requiredCount", "2", "notes", special));
        scheduler.assign(event.getId(), shift.getId(), volunteer.getId(), true);
        Shift overlap = scheduler.saveShift(event.getId(), null, Map.of("name", "Overlap", "departmentId", department.getId().toString(),
            "date", "2026-10-29", "startTime", "09:30", "endTime", "10:30", "requiredCount", "1"));
        scheduler.assign(event.getId(), overlap.getId(), volunteer.getId(), true);
        scheduler.saveShift(event.getId(), null, Map.of("name", "Unassigned", "departmentId", department.getId().toString(),
            "date", "2026-10-29", "startTime", "11:00", "endTime", "12:00", "requiredCount", "1"));
    }
    @AfterEach void cleanup() { scheduler.deleteEvent(event.getId()); }

    @Test void roundTripRestoresAllFieldsAndRelationshipsWithNewIds() {
        var backup = backups.capture(event.getId());
        var counts = backups.restore(event.getId(), backup.files());
        assertThat(counts).containsEntry("departments", 2).containsEntry("volunteers", 2).containsEntry("shifts", 3).containsEntry("assignments", 2);
        var result = scheduler.snapshot(event.getId());
        assertThat(result.departments()).extracting(Department::getId).doesNotContain(department.getId());
        assertThat(result.departments()).extracting(Department::getDescription).contains("'original\nnotes");
        Volunteer restored = result.volunteers().stream().filter(v -> v.getEmail().equals("alex@example.com")).findFirst().orElseThrow();
        assertThat(restored.getNotes()).isEqualTo(special.strip());
        assertThat(restored.getPhone()).isEqualTo(volunteer.getPhone());
        assertThat(restored.getCongregation()).isEqualTo("North");
        assertThat(restored.getPrivilege()).isEqualTo(Volunteer.Privilege.ELDER);
        assertThat(restored.getDepartmentRoles().values()).containsExactly(Volunteer.Role.VOLUNTEER);
        assertThat(restored.getDepartmentRoles().keySet()).extracting(Department::getName).containsExactly(special.strip());
        assertThat(result.shifts().stream().filter(s -> s.getName().equals("Morning")).findFirst().orElseThrow().getNotes()).isEqualTo(special.strip());
        assertThat(scheduler.event(event.getId()).getVenue()).isEqualTo("Hall");
    }

    @Test void rejectsMixedSnapshotsWithoutChangingExistingData() {
        var files = new HashMap<>(backups.capture(event.getId()).files());
        files.put("shifts", backups.capture(event.getId()).files().get("shifts"));
        assertThatThrownBy(() -> backups.restore(event.getId(), files)).hasMessageContaining("same backup snapshot");
        assertThat(scheduler.shift(event.getId(), shift.getId()).getName()).isEqualTo("Morning");
    }

    @Test void rejectsBrokenReferencesAndMalformedFilesBeforeReplacement() {
        var original = backups.capture(event.getId()).files();
        var files = new HashMap<>(original);
        String csv = new String(files.get("shifts"), StandardCharsets.UTF_8);
        files.put("shifts", csv.replace("\"" + shift.getId() + "\",\"" + department.getId() + "\",\"Morning\"",
            "\"" + shift.getId() + "\",\"999999999\",\"Morning\"").getBytes(StandardCharsets.UTF_8));
        var broken = files;
        assertThatThrownBy(() -> backups.restore(event.getId(), broken)).hasMessageContaining("shifts row").hasMessageContaining("Unknown reference");
        assertThat(scheduler.snapshot(event.getId()).assignments()).hasSize(2);
        for (String invalid : List.of("", "\"unclosed", "\"scheduler-backup\",\"999\",\"x\",\"departments\"\r\n")) {
            files = new HashMap<>(original);
            files.put("departments", invalid.getBytes(StandardCharsets.UTF_8));
            var bad = files;
            assertThatThrownBy(() -> backups.restore(event.getId(), bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(scheduler.department(event.getId(), department.getId())).isNotNull();
    }

    @Test void emptyBackupCanBeRestoredAndOtherEventsAreUntouched() {
        Event empty = scheduler.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2026-10-29", "venue", "Other"));
        try {
            var backup = backups.capture(empty.getId());
            Department otherDepartment = scheduler.saveDepartment(empty.getId(), null, Map.of("name", "Keep this department"));
            backups.restore(event.getId(), backup.files());
            assertThat(scheduler.snapshot(event.getId()).volunteers()).isEmpty();
            assertThat(scheduler.department(empty.getId(), otherDepartment.getId()).getName()).isEqualTo("Keep this department");
        } finally { scheduler.deleteEvent(empty.getId()); }
    }

    @Test void rejectsDuplicateIdsUnsupportedVersionsAndInvalidDates() {
        var original = backups.capture(event.getId()).files();
        String departmentCsv = new String(original.get("departments"), StandardCharsets.UTF_8);
        String shiftCsv = new String(original.get("shifts"), StandardCharsets.UTF_8);
        for (String kind : List.of("version", "duplicate", "date", "missing")) {
            var files = new HashMap<>(original);
            switch (kind) {
                case "version" -> files.put("departments", departmentCsv.replace("\"scheduler-backup\",\"1\"", "\"scheduler-backup\",\"2\"").getBytes(StandardCharsets.UTF_8));
                case "duplicate" -> files.put("departments", (departmentCsv + "\"" + department.getId() + "\",\"Duplicate\",\"\"\r\n").getBytes(StandardCharsets.UTF_8));
                case "date" -> files.put("shifts", shiftCsv.replace("2026-10-29", "2000-01-01").getBytes(StandardCharsets.UTF_8));
                default -> files.remove("volunteers");
            }
            assertThatThrownBy(() -> backups.restore(event.getId(), files)).isInstanceOf(IllegalArgumentException.class);
            assertThat(scheduler.shift(event.getId(), shift.getId()).getName()).isEqualTo("Morning");
        }
    }

    @Test void preservesLeadingApostrophesAndFormulaTextExactly() {
        for (String text : List.of("'original", "''two", "  =SUM(1,2)", "+formula", "@formula", "-formula", "\t=1+1", "quote\"comma,\r\n雪")) {
            volunteer.setNotes(text);
            volunteers.saveAndFlush(volunteer);
            backups.restore(event.getId(), backups.capture(event.getId()).files());
            volunteer = scheduler.snapshot(event.getId()).volunteers().stream().filter(v -> v.getEmail().equals("alex@example.com")).findFirst().orElseThrow();
            assertThat(volunteer.getNotes()).isEqualTo(text);
        }
    }

    @Test void reportOffersBackupsAndDownloadsOneSnapshotIgnoringFilters() throws Exception {
        var session = new MockHttpSession();
        String base = "/events/" + event.getId();
        mvc.perform(get(base + "/reports").param("type", "backup").session(session))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Restore event from CSV backup")))
            .andExpect(model().attribute("backupCounts", Map.of("volunteers", 2, "shifts", 3, "departments", 2)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Volunteers: 2 · Schedule/shifts: 3 · Departments: 2")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">Download CSV</a>"))));
        var captured = (BackupService.Backup) session.getAttribute("csvBackup");
        for (String type : List.of("volunteers", "departments", "shifts")) {
            mvc.perform(get(base + "/reports").param("type", type).session(session))
                .andExpect(status().isOk()).andExpect(model().attribute("reportType", "backup"));
            mvc.perform(get(base + "/reports.csv").param("type", type).param("departmentId", "999999")
                .param("date", "2000-01-01").param("snapshot", captured.token()).session(session))
                .andExpect(status().isOk()).andExpect(content().bytes(captured.files().get(type)));
        }
        mvc.perform(post(base + "/backups/refresh").session(session)).andExpect(status().isForbidden());
        scheduler.saveDepartment(event.getId(), null, Map.of("name", "New department"));
        mvc.perform(post(base + "/backups/refresh").with(csrf()).session(session))
            .andExpect(redirectedUrl(base + "/reports?type=backup"));
        assertThat(((BackupService.Backup) session.getAttribute("csvBackup")).token()).isNotEqualTo(captured.token());
        mvc.perform(get(base + "/reports").param("type", "backup").session(session))
            .andExpect(model().attribute("backupCounts", Map.of("volunteers", 2, "shifts", 3, "departments", 3)));
    }

    @Test void reportsOfferAutomaticFiltersAndEmptyBackupCounts() throws Exception {
        String base = "/events/" + event.getId();
        String html = mvc.perform(get(base + "/reports")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("name=\"type\" data-auto-submit", "name=\"departmentId\" data-auto-submit", ">Backup Data</option>")
            .doesNotContain("(backup)</option>", ">Apply</button", ">Clear</a>");
        var empty = scheduler.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION", "startDate", "2026-10-29", "venue", "Empty"));
        try {
            mvc.perform(get("/events/" + empty.getId() + "/reports").param("type", "backup"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Volunteers: 0 · Schedule/shifts: 0 · Departments: 0")));
        } finally { scheduler.deleteEvent(empty.getId()); }
    }

    @Test void restoreRequiresConfirmationAndCsrfAndAcceptsValidUpload() throws Exception {
        var backup = backups.capture(event.getId());
        String path = "/events/" + event.getId() + "/backups/restore";
        mvc.perform(multipart(path).file(file(backup, "departments")).file(file(backup, "volunteers")).file(file(backup, "shifts")))
            .andExpect(status().isForbidden());
        mvc.perform(multipart(path).file(file(backup, "departments")).file(file(backup, "volunteers")).file(file(backup, "shifts")).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertThat(scheduler.shift(event.getId(), shift.getId())).isNotNull();
        mvc.perform(multipart(path).file(file(backup, "departments")).file(file(backup, "volunteers")).file(file(backup, "shifts"))
            .param("confirmReplace", "true").with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice"));
        assertThat(scheduler.snapshot(event.getId()).assignments()).hasSize(2);
    }
    @Test void noDepartmentBackupsRoundTripAndFirstAssignmentPersistsMembership() {
        Volunteer unassigned = scheduler.saveVolunteer(event.getId(), null, Map.of("name", "Pending", "phone", "8135160335",
            "email", "pending@example.com", "congregation", "North", "privilege", "ELDER", "departmentChoice", "none"));
        assertThat(unassigned.getDepartmentRoles()).isEmpty();
        var backup = backups.capture(event.getId());
        backups.restore(event.getId(), backup.files());
        var restored = scheduler.snapshot(event.getId());
        Volunteer pending = restored.volunteers().stream().filter(v -> v.getEmail().equals("pending@example.com")).findFirst().orElseThrow();
        assertThat(pending.getDepartmentRoles()).isEmpty();
        assertThat(restored.assignments()).hasSize(2);
        Shift available = restored.shifts().stream().filter(s -> s.getName().equals("Unassigned")).findFirst().orElseThrow();
        scheduler.assign(event.getId(), available.getId(), pending.getId(), false);
        var savedRoles = scheduler.volunteer(event.getId(), pending.getId()).getDepartmentRoles();
        assertThat(savedRoles.values()).containsExactly(Volunteer.Role.VOLUNTEER);
        assertThat(savedRoles.keySet()).extracting(Department::getId).containsExactly(available.getDepartment().getId());
    }

    private MockMultipartFile file(BackupService.Backup backup, String type) {
        return new MockMultipartFile(type, type + ".csv", "text/csv", backup.files().get(type));
    }
}
