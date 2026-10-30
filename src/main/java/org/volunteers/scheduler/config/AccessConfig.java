package org.volunteers.scheduler.config;

import jakarta.servlet.http.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.volunteers.scheduler.service.PhoneAccess;

@Configuration
public class AccessConfig implements WebMvcConfigurer {
    private final PhoneAccess access;
    public AccessConfig(PhoneAccess access) { this.access = access; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                String path = org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
                if (path.matches("/(login|signup|error)") || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")) return true;
                HttpSession session = request.getSession(false);
                if (!access.signedIn(session)) { response.sendRedirect(request.getContextPath() + "/login"); return false; }
                Matcher match = Pattern.compile("^/events/(\\d+)(?:/.*)?$").matcher(path);
                Long eventId = match.matches() ? Long.valueOf(match.group(1)) : session == null ? null : (Long) session.getAttribute("activeEvent");
                boolean manager = access.canManage(session, eventId);
                if (path.equals("/events/save") || path.equals("/events")) {
                    String id = request.getParameter(path.equals("/events") ? "edit" : "id");
                    try { manager = access.canManage(session, id == null || id.isBlank() ? null : Long.valueOf(id)); }
                    catch (NumberFormatException ex) { response.sendError(400); return false; }
                }
                boolean restrictedTools = path.equals("/logs") || path.equals("/settings/notifications")
                    || path.startsWith("/settings/notifications/");
                if (restrictedTools && !access.canManageLogsAndNotifications(session, eventId)) {
                    response.sendError(403); return false;
                }
                if ((path.matches("/events/\\d+/delete") || path.matches("/events/\\d+/backups/restore")) && !access.canAdminister(session, eventId)) { response.sendError(403); return false; }
                boolean safe = Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
                boolean personal = path.equals("/logout") || path.equals("/settings") || path.equals("/api/notifications/read")
                    || path.equals("/api/device-visits") || path.matches("/events/\\d+/select");
                boolean ownEdit = false;
                if (path.matches("/events/\\d+/volunteers/save") || (safe && path.matches("/events/\\d+/volunteers"))) {
                    String id = request.getParameter(safe ? "edit" : "id");
                    try { ownEdit = id != null && access.ids(session).contains(Long.valueOf(id)); } catch (NumberFormatException ignored) { }
                }
                boolean editor = request.getParameter("edit") != null || "true".equals(request.getParameter("create"));
                if ((!safe && !personal && !manager && !ownEdit) || (safe && editor && !manager && !ownEdit)) {
                    response.sendError(403); return false;
                }
                response.setHeader("Cache-Control", "no-store");
                return true;
            }
        });
    }
}
