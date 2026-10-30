package org.volunteers.scheduler;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.repository.Volunteers;
import org.volunteers.scheduler.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:role-repair-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class VolunteerRoleConstraintIntegrationTest extends ManagerAccessFixture {
    @Autowired SchedulerService scheduler;
    @Autowired VolunteerImportService imports;
    @Autowired Volunteers volunteers;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;

    private static final String HEADER = "Name,Phone,Email,Congregation,Privilege,Notes,Department: Cleaning\r\n";
    private static final String ERROR = "Import failed because the database rejected a record. No volunteers were imported. Contact the administrator.";

    @Test void staleConstraintRollsBackImportAndRepairAcceptsEveryRole() throws Exception {
        long eventId = scheduler.saveEvent(null, Map.of("name", "Role repair test", "type", "REGIONAL_CONVENTION",
                "startDate", "2026-11-01", "endDate", "2026-11-02", "venue", "Test Hall")).getId();
        Department department = scheduler.saveDepartment(eventId, null, Map.of("name", "Cleaning"));
        imports.importFile(eventId, csv(HEADER + "Existing Volunteer,2025550000,existing@example.com,North,Publisher,,Volunteer\r\n"));
        Long existingId = volunteers.findByEventIdOrderByNameAscIdAsc(eventId).stream().filter(v -> !v.isEventAdministrator()).toList().getFirst().getId();
        jdbc.execute("ALTER TABLE volunteer_department_roles ADD CONSTRAINT volunteer_department_roles_department_role_check "
                + "CHECK (department_role IN ('ADMIN','OVERSEER','ASSISTANT_OVERSEER','CAPTAIN','VOLUNTEER'))");
        MockHttpSession session = new MockHttpSession(); session.setAttribute("activeEvent", eventId);
        // Put the rejected role last so the failure can occur during transaction commit.
        String file = HEADER
                + "First Import,2025550001,first@example.com,North,Publisher,,Volunteer\r\n"
                + "Second Import,2025550002,second@example.com,North,Elder,,Key Man\r\n";
        mvc.perform(multipart("/settings/data/volunteers/import").file(csv(file)).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/settings#data"))
                .andExpect(flash().attribute("importError", ERROR));
        assertThat(volunteers.findByEventIdOrderByNameAscIdAsc(eventId).stream().filter(v -> !v.isEventAdministrator()).toList()).extracting(Volunteer::getId).containsExactly(existingId);
        mvc.perform(get("/settings").session(session))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(ERROR)));

        // Exercise the actual repair SQL, including safe re-execution.
        for (int i = 0; i < 2; i++) {
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/sql/repair-volunteer-role-constraint.sql"));
            }
        }
        StringBuilder roles = new StringBuilder(HEADER);
        int number = 10;
        for (Volunteer.Role role : Volunteer.Role.values()) {
            if (role == Volunteer.Role.ADMIN) continue;
            roles.append("Role ").append(role.name()).append(",20255500").append(number++)
                    .append(",test@example.com,North,Elder,,").append(role.getLabel()).append("\r\n");
        }
        assertThat(imports.importFile(eventId, csv(roles.toString()))).isEqualTo(Volunteer.Role.values().length - 1);
        assertThat(volunteers.findByEventIdOrderByNameAscIdAsc(eventId).stream().filter(v -> !v.isEventAdministrator()).toList()).hasSize(6)
                .anySatisfy(v -> assertThat(v.getId()).isEqualTo(existingId));
        assertThatThrownBy(() -> jdbc.update("UPDATE volunteer_department_roles SET department_role = 'UNKNOWN' WHERE volunteer_id = ? AND department_id = ?", existingId, department.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        long sampleEvent = scheduler.saveEvent(null, Map.of("name", "Cleaning sample", "type", "REGIONAL_CONVENTION",
                "startDate", "2026-11-01", "endDate", "2026-11-02", "venue", "Test Hall")).getId();
        scheduler.saveDepartment(sampleEvent, null, Map.of("name", "Cleaning"));
        String samplePath = System.getenv("VOLUNTEER_IMPORT_TEST_CSV");
        String sample;
        if (samplePath != null && !samplePath.isBlank()) {
            // Optional local regression input; never copy personal contact data into the repository.
            sample = Files.readString(Path.of(samplePath), StandardCharsets.UTF_8);
        } else {
            StringBuilder generated = new StringBuilder(HEADER);
            for (int i = 0; i < 21; i++) generated.append("Sample ").append(i).append(",202555")
                    .append(String.format(Locale.ROOT, "%04d", i)).append(",test@example.com,\"North, Test\",Publisher,,")
                    .append(i == 2 ? "Key Man " : i == 3 ? "Captain" : "Volunteer").append("\r\n");
            sample = generated.toString();
        }
        assertThat(imports.importFile(sampleEvent, csv(sample))).isEqualTo(21);
        assertThat(volunteers.findByEventIdOrderByNameAscIdAsc(sampleEvent)).hasSize(22)
                .anySatisfy(v -> assertThat(v.getDepartmentRoles().values()).contains(Volunteer.Role.KEY_MAN))
                .anySatisfy(v -> assertThat(v.getDepartmentRoles().values()).contains(Volunteer.Role.CAPTAIN));
    }

    private MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "volunteers.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }
}
