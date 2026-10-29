package org.volunteers.scheduler.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class DeviceCookieFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
        String id = Arrays.stream(Optional.ofNullable(request.getCookies()).orElse(new Cookie[0]))
                .filter(c -> c.getName().equals("schedulerDevice"))
                .map(Cookie::getValue).filter(v -> v.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .findFirst().orElse(null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            Cookie cookie = new Cookie("schedulerDevice", id);
            cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
            cookie.setHttpOnly(true); cookie.setSecure(request.isSecure());
            cookie.setMaxAge(365 * 24 * 60 * 60); cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);
        }
        request.setAttribute("schedulerDeviceId", id);
        chain.doFilter(request, response);
    }
}
