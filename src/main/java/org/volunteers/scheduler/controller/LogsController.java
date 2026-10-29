package org.volunteers.scheduler.controller;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.volunteers.scheduler.repository.DeviceVisits;
import org.volunteers.scheduler.service.DeviceVisitService;

@Controller
public class LogsController {
    private final DeviceVisits visits;
    private final DeviceVisitService service;
    private final String password;
    public LogsController(DeviceVisits visits, DeviceVisitService service,
                          @Value("${scheduler.logs.password:snow}") String password) {
        this.visits = visits; this.service = service; this.password = password;
    }
    @GetMapping("/logs")
    String logs(@RequestParam(defaultValue = "0") int page, HttpSession session, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "no-store");
        boolean unlocked = Boolean.TRUE.equals(session.getAttribute("logsUnlocked"));
        model.addAttribute("event", null);
        model.addAttribute("unlocked", unlocked);
        if (unlocked) model.addAttribute("logs", visits.findAll(PageRequest.of(Math.max(0, page), 25,
                Sort.by(Sort.Order.desc("lastSeen"), Sort.Order.asc("id")))));
        return "pages/logs";
    }
    @PostMapping("/logs/unlock")
    String unlock(@RequestParam String password, HttpSession session, HttpServletResponse response,
                  org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        response.setHeader("Cache-Control", "no-store");
        synchronized (session) {
            long now = Instant.now().getEpochSecond();
            Long start = (Long) session.getAttribute("logsAttemptStart");
            if (start == null || now - start >= 300) {
                session.setAttribute("logsAttemptStart", now);
                session.setAttribute("logsAttempts", 0);
            }
            int attempts = (Integer) session.getAttribute("logsAttempts");
            if (attempts >= 5) {
                flash.addFlashAttribute("logsError", "Too many attempts. Try again in five minutes.");
            } else if (MessageDigest.isEqual(this.password.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8))) {
                session.setAttribute("logsUnlocked", true);
                session.removeAttribute("logsAttemptStart");
                session.removeAttribute("logsAttempts");
            } else {
                session.setAttribute("logsAttempts", attempts + 1);
                flash.addFlashAttribute("logsError", "Incorrect password.");
            }
        }
        return "redirect:/logs";
    }
    @PostMapping("/logs/lock")
    String lock(HttpSession session, HttpServletResponse response) {
        session.removeAttribute("logsUnlocked");
        response.setHeader("Cache-Control", "no-store");
        return "redirect:/logs";
    }
    @PostMapping("/api/device-visits")
    @ResponseBody
    ResponseEntity<Void> record(@RequestBody Map<String, String> data, HttpServletRequest request) {
        String id = (String) request.getAttribute("schedulerDeviceId");
        service.record(id, data);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
