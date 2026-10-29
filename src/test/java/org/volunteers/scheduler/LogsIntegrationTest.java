package org.volunteers.scheduler;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.volunteers.scheduler.repository.DeviceVisits;
import org.volunteers.scheduler.service.DeviceVisitService;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:logs-tests;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class LogsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DeviceVisits visits;
    @Autowired DeviceVisitService service;
    @BeforeEach void clear() { visits.deleteAll(); }

    @Test void passwordGateSessionLockAndCsrf() throws Exception {
        service.record(UUID.randomUUID().toString(), Map.of("city", "PrivateCity"));
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/logs").session(session)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PrivateCity"))));
        mvc.perform(post("/logs/unlock").session(session).param("password", "snow")).andExpect(status().isForbidden());
        mvc.perform(post("/logs/unlock").session(session).with(csrf()).param("password", "wrong"))
            .andExpect(flash().attribute("logsError", "Incorrect password."));
        assertThat(session.getAttribute("logsUnlocked")).isNull();
        mvc.perform(post("/logs/unlock").session(session).with(csrf()).param("password", "snow"))
            .andExpect(redirectedUrl("/logs"));
        mvc.perform(get("/logs").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("PrivateCity")));
        mvc.perform(post("/logs/lock").session(session).with(csrf())).andExpect(redirectedUrl("/logs"));
        assertThat(session.getAttribute("logsUnlocked")).isNull();
    }

    @Test void failedAttemptsAreLimitedAndExpire() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 5; i++) mvc.perform(post("/logs/unlock").session(session).with(csrf()).param("password", "wrong"));
        mvc.perform(post("/logs/unlock").session(session).with(csrf()).param("password", "snow"))
            .andExpect(flash().attribute("logsError", "Too many attempts. Try again in five minutes."));
        assertThat(session.getAttribute("logsUnlocked")).isNull();
        session.setAttribute("logsAttemptStart", 0L);
        mvc.perform(post("/logs/unlock").session(session).with(csrf()).param("password", "snow"));
        assertThat(session.getAttribute("logsUnlocked")).isEqualTo(true);
    }

    @Test void recordingUsesPersistentCookieAndPreservesLocation() throws Exception {
        mvc.perform(post("/api/device-visits").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        var result = mvc.perform(post("/api/device-visits").with(csrf()).contentType("application/json")
            .content("{\"browser\":\"Firefox 140\",\"city\":\"Austin\"}"))
            .andExpect(status().isNoContent()).andReturn();
        Cookie cookie = result.getResponse().getCookie("schedulerDevice");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        mvc.perform(post("/api/device-visits").with(csrf()).cookie(cookie).contentType("application/json").content("{}"))
            .andExpect(status().isNoContent());
        var record = visits.findById(cookie.getValue()).orElseThrow();
        assertThat(record.visitCount).isEqualTo(2);
        assertThat(record.city).isEqualTo("Austin");
        assertThat(record.name).isNull();
        assertThat(record.lastSeen).isAfterOrEqualTo(record.firstSeen);
        mvc.perform(post("/api/device-visits").with(csrf()).contentType("application/json").content("{}"));
        assertThat(visits.count()).isEqualTo(2);
    }

    @Test void concurrentUpdatesPreserveCount() throws Exception {
        String id = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> service.record(id, Map.of())));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(visits.findById(id).orElseThrow().visitCount).isEqualTo(20);
    }

    @Test void emptyPaginationAndEscaping() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("logsUnlocked", true);
        mvc.perform(get("/logs").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("No device visits recorded yet.")));
        for (int i = 0; i < 26; i++) service.record(UUID.randomUUID().toString(), Map.of("browser", "<script>alert(1)</script>"));
        mvc.perform(get("/logs").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Next")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
        mvc.perform(get("/logs").param("page", "1").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Previous")));
    }
}
