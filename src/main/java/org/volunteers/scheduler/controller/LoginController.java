package org.volunteers.scheduler.controller;

import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.volunteers.scheduler.model.Volunteer;
import org.volunteers.scheduler.service.*;

@Controller
public class LoginController {
    private final PhoneAccess access;
    private final SchedulerService scheduler;
    public LoginController(PhoneAccess access, SchedulerService scheduler) { this.access = access; this.scheduler = scheduler; }
    @GetMapping("/login") String login() { return "pages/login"; }
    @PostMapping("/login") String login(@RequestParam String phone, HttpServletRequest request, RedirectAttributes flash) {
        String normalized = PhoneAccess.normalize(phone);
        if (normalized.isEmpty() || access.matching(normalized).isEmpty()) {
            flash.addFlashAttribute("error", normalized.isEmpty() ? "Enter a valid 10-digit phone number." : "Phone number not found. Please sign up.");
            flash.addFlashAttribute("phone", phone); return "redirect:/login";
        }
        request.getSession(); request.changeSessionId();
        request.getSession().setAttribute(PhoneAccess.SESSION, normalized);
        request.getSession().removeAttribute("activeEvent");
        return "redirect:/events";
    }
    @GetMapping("/signup") String signup(Model model) {
        model.addAttribute("events", scheduler.events()); model.addAttribute("signup", true);
        model.addAttribute("showEditor", true); model.addAttribute("editId", null);
        model.addAttribute("volunteerPrivileges", Volunteer.Privilege.values());
        if (!model.containsAttribute("draft")) model.addAttribute("draft", Map.of());
        return "pages/signup";
    }
    @PostMapping("/signup") String signup(@RequestParam long eventId, @RequestParam Map<String,String> input,
                                          HttpServletRequest request, RedirectAttributes flash) {
        try {
            Volunteer v = access.signup(eventId, input);
            request.getSession(); request.changeSessionId();
            request.getSession().setAttribute(PhoneAccess.SESSION, PhoneAccess.normalize(v.getPhone()));
            return "redirect:/events";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            Map<String,String> draft = new HashMap<>(input); draft.remove("_csrf");
            flash.addFlashAttribute("draft", draft); return "redirect:/signup";
        }
    }
    @PostMapping("/logout") String logout(HttpSession session) { session.invalidate(); return "redirect:/login"; }
}
