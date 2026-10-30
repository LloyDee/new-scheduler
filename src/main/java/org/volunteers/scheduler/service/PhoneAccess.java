package org.volunteers.scheduler.service;

import java.util.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.repository.*;

@Service
public class PhoneAccess {
    public static final String SESSION = "loginPhone";
    private final Volunteers volunteers;
    private final Events events;
    private final SchedulerService scheduler;
    public PhoneAccess(Volunteers volunteers, Events events, SchedulerService scheduler) {
        this.volunteers = volunteers; this.events = events; this.scheduler = scheduler;
    }
    public static String normalize(String phone) {
        if (phone == null || !phone.matches("[+0-9() .-]+")) return "";
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.startsWith("1")) digits = digits.substring(1);
        return digits.length() == 10 ? digits : "";
    }
    public List<Volunteer> matching(String phone) {
        String key = normalize(phone);
        if (key.isEmpty()) return List.of();
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && Set.of("GET", "HEAD").contains(servlet.getRequest().getMethod())) {
            String cacheKey = PhoneAccess.class.getName() + ".matching." + key;
            Object cached = servlet.getRequest().getAttribute(cacheKey);
            if (cached instanceof List<?>) {
                @SuppressWarnings("unchecked") List<Volunteer> result = (List<Volunteer>) cached;
                return result;
            }
            List<Volunteer> result = volunteers.findByNormalizedPhone(key);
            servlet.getRequest().setAttribute(cacheKey, result);
            return result;
        }
        return volunteers.findByNormalizedPhone(key);
    }
    public boolean signedIn(HttpSession session) {
        return !permissions(session).isEmpty();
    }
    public Set<Long> ids(HttpSession session) {
        Set<Long> ids = new HashSet<>();
        permissions(session).forEach(row -> ids.add(row.getVolunteerId()));
        return ids;
    }
    public boolean canManage(HttpSession session, Long eventId) {
        return permissions(session).stream()
            .filter(row -> eventId == null || row.getEventId().equals(eventId))
            .anyMatch(row -> managementRole(row.getRole()));
    }
    public Long department(HttpSession session, long eventId) {
        return permissions(session).stream().filter(row -> row.getEventId().equals(eventId))
            .map(Volunteers.AccessRow::getDepartmentId).filter(Objects::nonNull).min(Long::compareTo).orElse(null);
    }
    public Set<Long> manageableEventIds(HttpSession session) {
        Set<Long> result = new HashSet<>();
        permissions(session).stream().filter(row -> managementRole(row.getRole()))
            .forEach(row -> result.add(row.getEventId()));
        return result;
    }
    public String displayName(HttpSession session) {
        Long active = session == null ? null : (Long) session.getAttribute("activeEvent");
        return permissions(session).stream().sorted(Comparator
            .comparing((Volunteers.AccessRow row) -> !Objects.equals(row.getEventId(), active))
            .thenComparing(Volunteers.AccessRow::getVolunteerId))
            .map(Volunteers.AccessRow::getName).filter(Objects::nonNull).findFirst().orElse(null);
    }
    private static boolean managementRole(Volunteer.Role role) {
        return role != null && Set.of(Volunteer.Role.ADMIN, Volunteer.Role.OVERSEER,
            Volunteer.Role.ASSISTANT_OVERSEER, Volunteer.Role.CAPTAIN).contains(role);
    }
    private List<Volunteers.AccessRow> permissions(HttpSession session) {
        String key = session == null ? "" : normalize((String) session.getAttribute(SESSION));
        if (key.isEmpty()) return List.of();
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)
                || servlet.getRequest().getMethod() == null || servlet.getRequest().getMethod().isBlank()) attributes = null;
        String cacheKey = PhoneAccess.class.getName() + ".permissions." + key;
        if (attributes != null) {
            Object cached = attributes.getAttribute(cacheKey, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof List<?>) {
                @SuppressWarnings("unchecked") var rows = (List<Volunteers.AccessRow>) cached;
                return rows;
            }
        }
        var rows = volunteers.findAccessByPhone(key);
        if (attributes != null) attributes.setAttribute(cacheKey, rows, RequestAttributes.SCOPE_REQUEST);
        return rows;
    }
    @Transactional
    public Event saveEvent(Long id, Map<String, String> input, HttpSession session) {
        Event event = scheduler.saveEvent(id, input);
        if (id == null) {
            Volunteer owner = matching((String) session.getAttribute(SESSION)).stream().findFirst().orElseThrow();
            Department administration = scheduler.saveDepartment(event.getId(), null, Map.of("name", "Administration"));
            Volunteer member = new Volunteer();
            member.setEvent(event); member.setName(owner.getName()); member.setPhone(owner.getPhone());
            member.setEmail(owner.getEmail()); member.setCongregation(owner.getCongregation());
            member.setNotes(""); member.setPrivilege(owner.getPrivilege());
            member.getDepartmentRoles().put(administration, Volunteer.Role.ADMIN);
            volunteers.save(member);
        }
        return event;
    }
    @Transactional
    public Volunteer signup(long eventId, Map<String, String> input) {
        // Lock all event rows in a stable order to serialize global registration checks.
        events.findAll().stream().map(Event::getId).sorted().forEach(id -> events.lockForVolunteerUpdate(id));
        String phone = normalize(input.get("phone"));
        if (phone.isEmpty()) throw new IllegalArgumentException("Enter a valid 10-digit phone number.");
        if (!matching(phone).isEmpty()) throw new IllegalArgumentException("This phone number already exists. Please log in.");
        Map<String, String> safe = new HashMap<>(input);
        safe.keySet().removeIf(k -> k.startsWith("departmentRole_"));
        safe.put("departmentChoice", "none"); safe.put("phone", phone);
        return scheduler.saveVolunteer(eventId, null, safe);
    }
    @Transactional
    public void saveOwn(long eventId, long id, Map<String, String> input, HttpSession session) {
        if (!ids(session).contains(id)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        scheduler.volunteer(eventId, id);
        String phone = normalize(input.get("phone"));
        if (phone.isEmpty()) throw new IllegalArgumentException("Enter a valid 10-digit phone number.");
        Set<Long> own = ids(session);
        if (matching(phone).stream().anyMatch(other -> !own.contains(other.getId())))
            throw new IllegalArgumentException("This phone number already exists.");
        // Apply contact changes to the same person's event records without altering memberships.
        for (Volunteer record : matching((String) session.getAttribute(SESSION))) {
            record.setName(SchedulerService.required(input, "name", 160));
            record.setCongregation(SchedulerService.required(input, "congregation", 160));
            String email = input.getOrDefault("email", "").strip();
            if (email.length() > 254 || (!email.isEmpty() && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
                throw new IllegalArgumentException("Enter a valid email address.");
            record.setEmail(email); record.setPhone("(" + phone.substring(0,3) + ")" + phone.substring(3,6) + "-" + phone.substring(6));
            String notes = input.getOrDefault("notes", "");
            if (notes.length() > 2000) throw new IllegalArgumentException("Notes are too long.");
            record.setNotes(notes);
            try { record.setPrivilege(Volunteer.Privilege.valueOf(input.getOrDefault("privilege", record.getPrivilege() == null ? "PUBLISHER" : record.getPrivilege().name()))); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Choose a valid privilege."); }
            volunteers.save(record);
        }
        session.setAttribute(SESSION, phone);
    }
}
