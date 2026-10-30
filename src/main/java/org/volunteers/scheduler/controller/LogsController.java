package org.volunteers.scheduler.controller;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
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
    private final org.volunteers.scheduler.service.PhoneAccess access;
    public LogsController(DeviceVisits visits, DeviceVisitService service,
                          org.volunteers.scheduler.service.PhoneAccess access) {
        this.visits = visits; this.service = service; this.access = access;
    }
    @GetMapping("/logs")
    String logs(@RequestParam(defaultValue = "0") int page, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("event", null);
        model.addAttribute("logs", visits.findAll(PageRequest.of(Math.max(0, page), 10,
                Sort.by(Sort.Order.desc("lastSeen"), Sort.Order.asc("id")))));
        return "pages/logs";
    }
    @PostMapping("/api/device-visits")
    @ResponseBody
    ResponseEntity<Void> record(@RequestBody Map<String, String> data, HttpServletRequest request) {
        String id = (String) request.getAttribute("schedulerDeviceId");
        HttpSession session = request.getSession(false);
        String name = access.displayName(session);
        // Separate people sharing a browser while preserving their device-level visit totals.
        if (name != null && session != null) id = UUID.nameUUIDFromBytes(
            (id + ":" + session.getAttribute(org.volunteers.scheduler.service.PhoneAccess.SESSION))
                .getBytes(StandardCharsets.UTF_8)).toString();
        service.record(id, data, name);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
