package org.volunteers.scheduler;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.volunteers.scheduler.model.Announcement;
import org.volunteers.scheduler.repository.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:notification-tests;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "scheduler.logs.password=test-secret"})
@AutoConfigureMockMvc
class NotificationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Announcements announcements;
    @Autowired AnnouncementReads reads;
    @BeforeEach void clear() { reads.deleteAll(); announcements.deleteAll(); }

    @Test void settingsPasswordGateValidationAndLock() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/settings").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("notification-bell")))
                .andExpect(content().string(not(containsString("Send notification</button>"))));
        mvc.perform(post("/settings/notifications").session(session).with(csrf()).param("message", "Blocked"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/settings/notifications/unlock").session(session).param("password", "test-secret"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/settings/notifications/unlock").session(session).with(csrf()).param("password", "snow"))
                .andExpect(flash().attribute("notificationError", "Incorrect password."));
        mvc.perform(post("/settings/notifications/unlock").session(session).with(csrf()).param("password", "test-secret"))
                .andExpect(redirectedUrl("/settings#notification"));
        assertThat(session.getAttribute("logsUnlocked")).isNull();
        mvc.perform(get("/settings").session(session)).andExpect(content().string(containsString("Send notification</button>")));
        for (String invalid : new String[]{"  ", "x".repeat(5001)}) {
            mvc.perform(post("/settings/notifications").session(session).with(csrf()).param("message", invalid))
                    .andExpect(flash().attribute("notificationDraft", invalid));
        }
        mvc.perform(post("/settings/notifications").session(session).param("message", "No CSRF"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/settings/notifications").session(session).with(csrf()).param("message", "Hello\n<script>alert(1)</script>"))
                .andExpect(flash().attribute("notice", "Notification sent."));
        assertThat(announcements.count()).isEqualTo(1);
        mvc.perform(post("/settings/notifications/lock").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/settings/notifications").session(session).with(csrf()).param("message", "Blocked"))
                .andExpect(status().isForbidden());
    }

    @Test void attemptsAreLimitedAndExpire() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 5; i++) mvc.perform(post("/settings/notifications/unlock").session(session).with(csrf()).param("password", "bad"));
        mvc.perform(post("/settings/notifications/unlock").session(session).with(csrf()).param("password", "test-secret"))
                .andExpect(flash().attribute("notificationError", "Too many attempts. Try again in five minutes."));
        assertThat(session.getAttribute("notificationUnlocked")).isNull();
        session.setAttribute("notificationAttemptStart", 0L);
        mvc.perform(post("/settings/notifications/unlock").session(session).with(csrf()).param("password", "test-secret"));
        assertThat(session.getAttribute("notificationUnlocked")).isEqualTo(true);
    }

    @Test void unreadIsPersistentPerDeviceAndOnlyAcknowledgedIdsDisappear() throws Exception {
        Announcement first = message("First");
        Announcement second = message("Second");
        var result = mvc.perform(get("/api/notifications/count")).andExpect(jsonPath("$.count").value(2))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        Cookie device = result.getResponse().getCookie("schedulerDevice");
        assertThat(device).isNotNull();
        assertThat(device.isHttpOnly()).isTrue();
        mvc.perform(get("/api/notifications").cookie(device)).andExpect(jsonPath("$[0].message").value("Second"));
        String body = "[" + first.id + "," + second.id + "]";
        mvc.perform(post("/api/notifications/read").cookie(device).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/notifications/read").cookie(device).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications").cookie(device).session(new MockHttpSession())).andExpect(content().json("[]"));
        mvc.perform(get("/api/notifications/count").cookie(device)).andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/api/notifications/count")).andExpect(jsonPath("$.count").value(2));
        message("New arrival");
        mvc.perform(get("/api/notifications").cookie(device)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].message").value("New arrival"));
    }

    private Announcement message(String text) {
        Announcement item = new Announcement(); item.message = text; item.createdAt = Instant.now();
        return announcements.save(item);
    }
}
