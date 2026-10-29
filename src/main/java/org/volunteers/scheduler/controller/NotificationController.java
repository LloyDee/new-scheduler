package org.volunteers.scheduler.controller;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.repository.*;

@Controller
public class NotificationController {
    private final Announcements announcements;
    private final AnnouncementReads reads;
    private final String password;

    public NotificationController(Announcements announcements, AnnouncementReads reads,
                                  @Value("${scheduler.logs.password:snow}") String password) {
        this.announcements = announcements; this.reads = reads; this.password = password;
    }

    @GetMapping("/api/notifications/count") @ResponseBody
    ResponseEntity<Map<String, Long>> count(@RequestAttribute("schedulerDeviceId") String device) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("count", announcements.unreadCount(device)));
    }

    @GetMapping("/api/notifications") @ResponseBody
    ResponseEntity<List<Announcement>> unread(@RequestAttribute("schedulerDeviceId") String device) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(announcements.unread(device));
    }

    @PostMapping("/api/notifications/read") @ResponseBody
    ResponseEntity<Void> read(@RequestAttribute("schedulerDeviceId") String device, @RequestBody List<Long> ids) {
        for (Long id : new HashSet<>(ids)) {
            if (id == null || !announcements.existsById(id)) continue;
            AnnouncementRead receipt = new AnnouncementRead();
            receipt.deviceId = device; receipt.announcementId = id;
            reads.save(receipt);
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/settings/notifications/unlock")
    String unlock(@RequestParam String password, HttpSession session, HttpServletResponse response, RedirectAttributes flash) {
        response.setHeader("Cache-Control", "no-store");
        synchronized (session) {
            long now = Instant.now().getEpochSecond();
            Long start = (Long) session.getAttribute("notificationAttemptStart");
            if (start == null || now - start >= 300) {
                session.setAttribute("notificationAttemptStart", now);
                session.setAttribute("notificationAttempts", 0);
            }
            int attempts = (Integer) session.getAttribute("notificationAttempts");
            if (attempts >= 5) flash.addFlashAttribute("notificationError", "Too many attempts. Try again in five minutes.");
            else if (MessageDigest.isEqual(this.password.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8))) {
                session.setAttribute("notificationUnlocked", true);
                session.removeAttribute("notificationAttemptStart"); session.removeAttribute("notificationAttempts");
            } else {
                session.setAttribute("notificationAttempts", attempts + 1);
                flash.addFlashAttribute("notificationError", "Incorrect password.");
            }
        }
        return "redirect:/settings#notification";
    }

    @PostMapping("/settings/notifications/lock")
    String lock(HttpSession session, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        session.removeAttribute("notificationUnlocked");
        return "redirect:/settings#notification";
    }

    @PostMapping("/settings/notifications")
    Object send(@RequestParam String message, HttpSession session, HttpServletResponse response, RedirectAttributes flash) {
        response.setHeader("Cache-Control", "no-store");
        if (!Boolean.TRUE.equals(session.getAttribute("notificationUnlocked"))) return ResponseEntity.status(403).build();
        if (message.isBlank() || message.length() > 5000) {
            flash.addFlashAttribute("notificationError", "Enter a message between 1 and 5,000 characters.");
            flash.addFlashAttribute("notificationDraft", message);
        } else {
            Announcement announcement = new Announcement();
            announcement.message = message.strip(); announcement.createdAt = Instant.now();
            announcements.save(announcement);
            flash.addFlashAttribute("notice", "Notification sent.");
        }
        return "redirect:/settings#notification";
    }
}
