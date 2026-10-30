package org.volunteers.scheduler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.repository.*;
import org.volunteers.scheduler.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:import-tests;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "scheduler.app-owner-phone=9995550000"})
@AutoConfigureMockMvc
class VolunteerImportIntegrationTest extends ManagerAccessFixture {
    @Autowired VolunteerImportService imports;
    @Autowired SchedulerService scheduler;
    @Autowired Volunteers volunteers;
    @Autowired MockMvc mvc;
    long eventId;
    static final String HEADER = "Name,Phone,Email,Congregation,Privilege,Notes,Department: Attendants\r\n";
    static final String ROW = "Alex Rivera,8135160335,alex@example.com,North,Publisher,Welcome,Volunteer\r\n";

    @BeforeEach void setup() {
        eventId = scheduler.saveEvent(null, Map.of("name", "Import " + UUID.randomUUID(), "type", "REGIONAL_CONVENTION",
                "startDate", "2026-11-01", "endDate", "2026-11-02", "venue", "Hall")).getId();
        scheduler.saveDepartment(eventId, null, Map.of("name", "Attendants"));
    }
    MockMultipartFile csv(String text) { return new MockMultipartFile("file", "volunteers.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8)); }
    List<Volunteer> saved() { return volunteers.findByEventIdOrderByNameAscIdAsc(eventId).stream().filter(v -> !v.isEventAdministrator()).toList(); }

    @Test void csvSupportsBomQuotedNotesAndNoDepartment() {
        assertThat(imports.importFile(eventId, csv("\uFEFF" + HEADER + ROW.replace("Welcome,Volunteer", "\"Welcome, team\",")))).isEqualTo(1);
        Volunteer v = saved().getFirst();
        assertThat(v.getNotes()).isEqualTo("Welcome, team");
        assertThat(v.getPhone()).isEqualTo("(813)516-0335");
        assertThat(v.getDepartmentRoles()).isEmpty();
    }

    @Test void excelFormatsAndMultipleDepartments() throws Exception {
        scheduler.saveDepartment(eventId, null, Map.of("name", "Parking"));
        for (boolean modern : List.of(false, true)) {
            try (Workbook book = modern ? new XSSFWorkbook() : new HSSFWorkbook()) {
                Sheet sheet = book.createSheet();
                String[] headers = (HEADER.strip() + ",Department: Parking").split(",");
                String suffix = modern ? "2" : "1";
                String[] values = {"Alex " + suffix, "012345678" + suffix, "alex@example.com", "North", "Ministerial Servant", "", "Volunteer", "Key Man"};
                for (int r = 0; r < 2; r++) {
                    Row row = sheet.createRow(r);
                    String[] data = r == 0 ? headers : values;
                    for (int c = 0; c < data.length; c++) row.createCell(c).setCellValue(data[c]);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(); book.write(out);
                assertThat(imports.importFile(eventId, new MockMultipartFile("file", modern ? "v.xlsx" : "v.xls", "application/octet-stream", out.toByteArray()))).isEqualTo(1);
            }
        }
        assertThat(saved()).hasSize(2).allSatisfy(v -> assertThat(v.getDepartmentRoles()).hasSize(2));
    }

    @Test void invalidLaterRowRollsBackAndReportsRow() {
        assertThatThrownBy(() -> imports.importFile(eventId, csv(HEADER + ROW + ROW.replace("Alex Rivera", "Ben").replace("8135160335", "123"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Row 3, Phone").hasMessageContaining("10-digit");
        assertThat(saved()).isEmpty();
    }

    @Test void duplicatesRejectWholeFileByNameOrPhone() {
        for (String duplicate : List.of(ROW.replace("Alex Rivera", " alex rivera ").replace("8135160335", "9999999999"),
                ROW.replace("Alex Rivera", "Ben").replace("8135160335", "(813)516-0335"))) {
            assertThatThrownBy(() -> imports.importFile(eventId, csv(HEADER + ROW + duplicate))).hasMessageContaining("Duplicate");
            assertThat(saved()).isEmpty();
        }
        imports.importFile(eventId, csv(HEADER + ROW));
        assertThatThrownBy(() -> imports.importFile(eventId, csv(HEADER + ROW.replace("Alex Rivera", "Ben"))))
                .hasMessageContaining("existing volunteer");
        assertThatThrownBy(() -> imports.importFile(eventId, csv(HEADER + ROW.replace("8135160335", "9999999999"))))
                .hasMessageContaining("existing volunteer");
        assertThat(saved()).hasSize(1);
    }

    @Test void invalidHeadersAndValuesAreRejected() {
        for (String text : List.of(HEADER.replace("Name,", "") + ROW, HEADER.replace("Notes", "Extra") + ROW,
                HEADER.replace("Notes", "NAME") + ROW, HEADER.replace("Attendants", "Unknown") + ROW,
                HEADER + ROW.replace("alex@example.com", "bad"), HEADER + ROW.replace("Publisher", "Unknown"),
                HEADER + ROW.replace("Volunteer", "Unknown"), HEADER + ROW.replace("North", ""),
                HEADER + ROW.replace("Alex Rivera", "a".repeat(161)), HEADER + ROW.replace(",Welcome,", ",Welcome,extra,"))) {
            assertThatThrownBy(() -> imports.importFile(eventId, csv(text))).isInstanceOf(IllegalArgumentException.class);
            assertThat(saved()).isEmpty();
        }
    }

    @Test void combinedLeadershipLimitsRollBack() {
        assertThatThrownBy(() -> imports.importFile(eventId, csv(HEADER + ROW.replace("Volunteer", "Overseer")
                + ROW.replace("Alex Rivera", "Ben").replace("8135160335", "9999999999").replace("Volunteer", "Overseer"))))
                .hasMessageContaining("at most 1");
        assertThat(saved()).isEmpty();
    }

    @Test void malformedEmptyUnsupportedAndFormulaFilesAreRejected() throws Exception {
        for (MockMultipartFile file : List.of(csv(""), csv(HEADER), csv(HEADER + "\"unclosed"),
                new MockMultipartFile("file", "v.txt", "text/plain", ROW.getBytes()),
                new MockMultipartFile("file", "v.xlsx", "application/octet-stream", ROW.getBytes()),
                new MockMultipartFile("file", "v.csv", "text/csv", new byte[10 * 1024 * 1024 + 1]))) {
            assertThatThrownBy(() -> imports.importFile(eventId, file)).isInstanceOf(IllegalArgumentException.class);
        }
        try (Workbook book = new XSSFWorkbook()) {
            book.createSheet().createRow(0).createCell(0).setCellFormula("1+1");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); book.write(out);
            assertThatThrownBy(() -> imports.importFile(eventId, new MockMultipartFile("file", "v.xlsx", "application/octet-stream", out.toByteArray())))
                    .hasMessageContaining("Formula");
        }
    }

    @Test void settingsTemplateImportAndCsrf() throws Exception {
        MockHttpSession session = new MockHttpSession(); session.setAttribute("activeEvent", eventId);
        mvc.perform(get("/settings").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Download CSV template")));
        mvc.perform(get("/settings/data/volunteers/template.csv").session(session)).andExpect(status().isOk())
                .andExpect(content().string(HEADER));
        mvc.perform(multipart("/settings/data/volunteers/import").file(csv(HEADER + ROW)).session(session)).andExpect(status().isForbidden());
        mvc.perform(multipart("/settings/data/volunteers/import").file(csv(HEADER + ROW)).with(csrf()))
                .andExpect(redirectedUrl("/settings#data")).andExpect(flash().attributeExists("importError"));
        mvc.perform(multipart("/settings/data/volunteers/import").file(csv(HEADER + ROW)).session(session).with(csrf()))
                .andExpect(redirectedUrl("/settings#data")).andExpect(flash().attribute("importNotice", "1 volunteer imported."));
    }

    @Test void duplicatesInAnotherEventDoNotBlockImport() {
        imports.importFile(eventId, csv(HEADER + ROW));
        long other = scheduler.saveEvent(null, Map.of("name", "Other", "type", "REGIONAL_CONVENTION",
                "startDate", "2026-11-01", "endDate", "2026-11-02", "venue", "Hall")).getId();
        assertThat(imports.importFile(other, csv(HEADER.replace(",Department: Attendants", "") + ROW.replace(",Volunteer", "")))).isEqualTo(1);
    }

    @Test void settingsWithoutActiveEventAndErrorFlash() throws Exception {
        mvc.perform(get("/settings")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Select an event")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("type=\"file\""))));
        MockHttpSession session = new MockHttpSession(); session.setAttribute("activeEvent", eventId);
        mvc.perform(multipart("/settings/data/volunteers/import").file(csv(HEADER + ROW.replace("Publisher", "Invalid"))).session(session).with(csrf()))
                .andExpect(redirectedUrl("/settings#data")).andExpect(flash().attribute("importError", org.hamcrest.Matchers.containsString("Row 2, Privilege")));
        mvc.perform(get("/settings").session(session))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Row 2, Privilege")));
    }
}
