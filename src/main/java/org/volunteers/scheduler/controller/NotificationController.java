package org.volunteers.scheduler.controller;

import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
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

    public NotificationController(Announcements announcements, AnnouncementReads reads) {
        this.announcements = announcements; this.reads = reads;
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

    @PostMapping("/settings/notifications")
    String send(@RequestParam String message, HttpServletResponse response, RedirectAttributes flash) {
        response.setHeader("Cache-Control", "no-store");
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
