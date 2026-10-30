package org.volunteers.scheduler.controller;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
import org.volunteers.scheduler.service.PhoneAccess;
@ControllerAdvice
public class AccessAdvice {
    private final PhoneAccess access;
    public AccessAdvice(PhoneAccess access) { this.access = access; }
    @ModelAttribute void access(Model model, HttpServletRequest request, HttpSession session, HttpServletResponse response) {
        response.addHeader("Vary", "X-Scheduler-Section");
        model.addAttribute("sectionRequest", "GET".equals(request.getMethod())
            && "main".equals(request.getHeader("X-Scheduler-Section")));
        Object handler = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof org.springframework.web.method.HandlerMethod method
                && (method.hasMethodAnnotation(ResponseBody.class)
                    || method.getBeanType().isAnnotationPresent(RestController.class))) return;
        Long eventId = (Long) session.getAttribute("activeEvent");
        var matcher = java.util.regex.Pattern.compile("^/events/(\\d+)(?:/.*)?$")
                .matcher(org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request));
        if (matcher.matches()) eventId = Long.valueOf(matcher.group(1));
        model.addAttribute("ownVolunteerIds", access.ids(session));
        model.addAttribute("canAdminister", access.canAdminister(session, eventId));
        model.addAttribute("isAppOwner", access.isAppOwner(session));
        model.addAttribute("canManageLogsAndNotifications", access.canManageLogsAndNotifications(session, eventId));
        model.addAttribute("canManage", access.canManage(session, eventId));
        model.addAttribute("canCreateEvent", access.canManage(session, null));
        String name = access.displayName(session);
        model.addAttribute("currentUserFirstName", name == null || name.isBlank() ? null : name.strip().split("\\s+", 2)[0]);
        Long volunteerId = eventId == null ? null : access.ownVolunteerId(session, eventId);
        if (volunteerId != null) {
            model.addAttribute("currentVolunteerId", volunteerId);
            model.addAttribute("currentVolunteerUrl", "/events/" + eventId + "/volunteers?view=" + volunteerId);
        }
        model.addAttribute("signup", false);
    }
}
