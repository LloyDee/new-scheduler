package org.volunteers.scheduler.service;

import org.volunteers.scheduler.model.Assignment;
import org.volunteers.scheduler.model.Department;
import org.volunteers.scheduler.model.Event;
import org.volunteers.scheduler.model.Shift;
import org.volunteers.scheduler.model.Volunteer;
import org.volunteers.scheduler.repository.Events;
import org.volunteers.scheduler.repository.Volunteers;
import org.volunteers.scheduler.repository.Departments;
import org.volunteers.scheduler.repository.Shifts;
import org.volunteers.scheduler.repository.Assignments;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class SchedulerService {
    private final Events events;
    private final Volunteers volunteers;
    private final Departments departments;
    private final Shifts shifts;
    private final Assignments assignments;

    public SchedulerService(Events events, Volunteers volunteers, Departments departments,
                            Shifts shifts, Assignments assignments) {
        this.events = events; this.volunteers = volunteers; this.departments = departments;
        this.shifts = shifts; this.assignments = assignments;
    }

    public List<Event> events() { return events.findAllByOrderByStartDateDescIdDesc(); }
    public Event event(long id) { return events.findById(id).orElseThrow(SchedulerService::notFound); }
    public Volunteer volunteer(long eventId, long id) {
        Volunteer v = volunteers.findById(id).orElseThrow(SchedulerService::notFound);
        sameEvent(eventId, v.getEvent().getId()); return v;
    }
    public Department department(long eventId, long id) {
        Department d = departments.findById(id).orElseThrow(SchedulerService::notFound);
        sameEvent(eventId, d.getEvent().getId()); return d;
    }
    public Shift shift(long eventId, long id) {
        Shift s = shifts.findById(id).orElseThrow(SchedulerService::notFound);
        sameEvent(eventId, s.getDepartment().getEvent().getId()); return s;
    }
    public Snapshot snapshot(long id) {
        return new Snapshot(event(id), volunteers.findByEventIdOrderByNameAscIdAsc(id),
                departments.findByEventIdOrderByNameAscIdAsc(id),
                shifts.findByDepartmentEventIdOrderByDateAscStartTimeAscIdAsc(id),
                assignments.findByShiftDepartmentEventIdOrderByIdAsc(id));
    }

    @Transactional
    public Event saveEvent(Long id, Map<String, String> input) {
        Event.Type type;
        try { type = Event.Type.valueOf(input.getOrDefault("type", "")); }
        catch (IllegalArgumentException ex) { throw invalid("Choose an event type."); }
        LocalDate start = date(input, "startDate");
        LocalDate end = type == Event.Type.REGIONAL_CONVENTION ? start.plusDays(2) : start;
        String assembly = "";
        String name;
        if (type == Event.Type.REGIONAL_CONVENTION) {
            int year = start.getYear() + (start.getMonthValue() >= 9 ? 1 : 0);
            name = year + " Regional Convention";
        } else {
            assembly = required(input, "assembly", 60);
            require(Set.of("with Circuit Overseer", "with Branch Representative").contains(assembly),
                    "Choose a Circuit Assembly representative.");
            name = "Circuit Assembly " + assembly;
        }
        String venue = optional(input, "venue", 240);
        Event e = id == null ? new Event() : event(id);
        if (id != null) {
            require(shifts.findByDepartmentEventIdOrderByDateAscStartTimeAscIdAsc(id).stream()
                    .noneMatch(s -> s.getDate().isBefore(start) || s.getDate().isAfter(end)),
                    "These dates exclude existing shifts. Move or delete those shifts first.");
        }
        e.setName(name); e.setType(type); e.setStartDate(start); e.setEndDate(end); e.setVenue(venue);
        e.setAssembly(assembly);
        return events.save(e);
    }

    @Transactional
    public Volunteer saveVolunteer(long eventId, Long id, Map<String, String> input) {
        Event e = events.lockForVolunteerUpdate(eventId).orElseThrow(SchedulerService::notFound);
        String name = required(input, "name", 160), phone = required(input, "phone", 60);
        require(phone.matches("[0-9() .-]+"), "Enter a 10-digit phone number.");
        String phoneDigits = phone.replaceAll("[^0-9]", "");
        require(phoneDigits.length() == 10, "Enter a 10-digit phone number.");
        phone = "(" + phoneDigits.substring(0, 3) + ")" + phoneDigits.substring(3, 6) + "-" + phoneDigits.substring(6);
        String email = required(input, "email", 254);
        require(email.isEmpty() || email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"), "Enter a valid email address.");
        String congregation = required(input, "congregation", 160), notes = optional(input, "notes", 2000);
        String privilegeValue = required(input, "privilege", 40);
        Volunteer.Privilege privilege = null;
        if (!privilegeValue.isEmpty()) {
            try { privilege = Volunteer.Privilege.valueOf(privilegeValue); }
            catch (IllegalArgumentException ex) { throw invalid("Choose a valid privilege."); }
        }
        Map<Department, Volunteer.Role> departmentRoles = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (!entry.getKey().startsWith("departmentRole_")) continue;
            if (entry.getValue().isBlank()) continue;
            long departmentId = number(Map.of("departmentId", entry.getKey().substring("departmentRole_".length())), "departmentId");
            Department selected = department(eventId, departmentId);
            Volunteer.Role role;
            try { role = Volunteer.Role.valueOf(entry.getValue()); }
            catch (IllegalArgumentException ex) { throw invalid("Choose a valid role for " + selected.getName() + "."); }
            departmentRoles.put(selected, role);
        }
        require(!departmentRoles.isEmpty(), "Choose at least one department and its role.");
        List<Volunteer> existing = volunteers.findByEventIdOrderByNameAscIdAsc(eventId);
        for (var membership : departmentRoles.entrySet()) {
            Volunteer.Role role = membership.getValue();
            int limit = role == Volunteer.Role.OVERSEER ? 1 : role == Volunteer.Role.ASSISTANT_OVERSEER ? 2 : Integer.MAX_VALUE;
            long count = existing.stream().filter(other -> !Objects.equals(other.getId(), id))
                    .filter(other -> other.getDepartmentRoles().entrySet().stream().anyMatch(entry ->
                            entry.getKey().getId().equals(membership.getKey().getId()) && entry.getValue() == role)).count();
            require(count < limit, membership.getKey().getName() + " allows at most " + limit + " "
                    + role.getLabel() + (limit == 1 ? "." : "s."));
        }
        Volunteer v = id == null ? new Volunteer() : volunteer(eventId, id);
        v.setEvent(e); v.setName(name); v.setPhone(phone); v.setEmail(email); v.setCongregation(congregation); v.setNotes(notes);
        v.setPrivilege(privilege);
        v.getDepartmentRoles().clear();
        v.getDepartmentRoles().putAll(departmentRoles);
        return volunteers.save(v);
    }

    @Transactional
    public Department saveDepartment(long eventId, Long id, Map<String, String> input) {
        Event e = event(eventId);
        String name = required(input, "name", 160), description = optional(input, "description", 2000);
        Department d = id == null ? new Department() : department(eventId, id);
        d.setEvent(e); d.setName(name); d.setDescription(description);
        return departments.save(d);
    }

    @Transactional
    public Shift saveShift(long eventId, Long id, Map<String, String> input) {
        Event e = event(eventId);
        Department d = department(eventId, number(input, "departmentId"));
        LocalDate date = e.getType() == Event.Type.CIRCUIT_ASSEMBLY ? e.getStartDate() : date(input, "date");
        LocalTime start = time(input, "startTime"), end = time(input, "endTime");
        if (e.getType() == Event.Type.CIRCUIT_ASSEMBLY) {
            for (LocalTime value : List.of(start, end)) {
                require(!value.isBefore(LocalTime.of(6, 0)) && !value.isAfter(LocalTime.of(17, 30)),
                        "Shift times must be between 06:00 AM and 05:30 PM.");
                require(value.getMinute() % 10 == 0 && value.getSecond() == 0 && value.getNano() == 0,
                        "Shift times must use 10-minute increments.");
            }
        }
        require(!date.isBefore(e.getStartDate()) && !date.isAfter(e.getEndDate()), "Shift date must be within the event dates.");
        require(end.isAfter(start), "End time must be after start time. Overnight shifts are not supported.");
        long required = number(input, "requiredCount");
        require(required >= 1 && required <= 10000, "Required volunteers must be between 1 and 10,000.");
        String notes = optional(input, "notes", 2000);
        String name = required(input, "name", 160);
        Shift s = id == null ? new Shift() : shift(eventId, id);
        if (id != null && !(date.equals(s.getDate()) && start.equals(s.getStartTime()) && end.equals(s.getEndTime()))) {
            List<Assignment> all = assignments.findByShiftDepartmentEventIdOrderByIdAsc(eventId);
            Set<Long> assigned = new HashSet<>();
            all.stream().filter(a -> a.getShift().getId().equals(id)).forEach(a -> assigned.add(a.getVolunteer().getId()));
            List<String> conflicts = all.stream().filter(a -> assigned.contains(a.getVolunteer().getId()) && !a.getShift().getId().equals(id)
                    && overlaps(a.getShift(), date, start, end)).map(SchedulerService::conflictDescription).toList();
            if (!conflicts.isEmpty() && !confirmed(input)) throw new OverlapException(String.join("; ", conflicts));
        }
        s.setName(name); s.setDepartment(d); s.setDate(date); s.setStartTime(start); s.setEndTime(end);
        s.setRequiredCount((int) required); s.setNotes(notes);
        return shifts.save(s);
    }

    @Transactional
    public void assign(long eventId, long shiftId, long volunteerId, boolean confirmed) {
        Shift s = shift(eventId, shiftId);
        Volunteer v = volunteer(eventId, volunteerId);
        require(!assignments.existsByShiftIdAndVolunteerId(shiftId, volunteerId), "This volunteer is already assigned to this shift.");
        List<String> conflicts = assignments.findByShiftDepartmentEventIdOrderByIdAsc(eventId).stream()
                .filter(a -> a.getVolunteer().getId().equals(volunteerId) && overlaps(a.getShift(), s.getDate(), s.getStartTime(), s.getEndTime()))
                .map(SchedulerService::conflictDescription).toList();
        if (!conflicts.isEmpty() && !confirmed) throw new OverlapException(String.join("; ", conflicts));
        Assignment a = new Assignment(); a.setShift(s); a.setVolunteer(v); assignments.saveAndFlush(a);
    }

    @Transactional
    public void unassign(long eventId, long id) {
        Assignment a = assignments.findById(id).orElseThrow(SchedulerService::notFound);
        sameEvent(eventId, a.getShift().getDepartment().getEvent().getId()); assignments.delete(a);
    }

    @Transactional
    public void deleteVolunteer(long eventId, long id) {
        Volunteer v = volunteer(eventId, id);
        assignments.deleteAll(assignments.findByShiftDepartmentEventIdOrderByIdAsc(eventId).stream()
                .filter(a -> a.getVolunteer().getId().equals(id)).toList());
        assignments.flush(); volunteers.delete(v);
    }

    @Transactional
    public void deleteShift(long eventId, long id) {
        Shift s = shift(eventId, id);
        assignments.deleteAll(assignments.findByShiftDepartmentEventIdOrderByIdAsc(eventId).stream()
                .filter(a -> a.getShift().getId().equals(id)).toList());
        assignments.flush(); shifts.delete(s); shifts.flush();
    }

    @Transactional
    public void deleteDepartment(long eventId, long id) {
        Department d = department(eventId, id);
        volunteers.findByEventIdOrderByNameAscIdAsc(eventId).forEach(v ->
                v.getDepartmentRoles().keySet().removeIf(selected -> selected.getId().equals(id)));
        volunteers.flush();
        shifts.findByDepartmentEventIdOrderByDateAscStartTimeAscIdAsc(eventId).stream()
                .filter(s -> s.getDepartment().getId().equals(id)).toList().forEach(s -> deleteShift(eventId, s.getId()));
        departments.delete(d); departments.flush();
    }

    @Transactional
    public void deleteEvent(long id) {
        Event e = event(id);
        assignments.deleteAll(assignments.findByShiftDepartmentEventIdOrderByIdAsc(id)); assignments.flush();
        shifts.deleteAll(shifts.findByDepartmentEventIdOrderByDateAscStartTimeAscIdAsc(id)); shifts.flush();
        volunteers.deleteAll(volunteers.findByEventIdOrderByNameAscIdAsc(id));
        volunteers.flush();
        departments.deleteAll(departments.findByEventIdOrderByNameAscIdAsc(id));
        volunteers.flush(); departments.flush(); events.delete(e);
    }

    static boolean overlaps(Shift s, LocalDate date, LocalTime start, LocalTime end) {
        return s.getDate().equals(date) && start.isBefore(s.getEndTime()) && end.isAfter(s.getStartTime());
    }
    private static String conflictDescription(Assignment a) {
        return a.getVolunteer().getName() + " — " + a.getShift().getDepartment().getName() + ", " + a.getShift().getDate() + " "
                + a.getShift().getStartTime() + "–" + a.getShift().getEndTime();
    }
    public static boolean confirmed(Map<String, String> input) { return "true".equals(input.get("confirmOverlap")); }
    public static long number(Map<String, String> input, String key) {
        try { return Long.parseLong(input.getOrDefault(key, "")); }
        catch (NumberFormatException ex) { throw invalid("Enter a valid value for " + key + "."); }
    }
    static LocalDate date(Map<String, String> input, String key) {
        try { return LocalDate.parse(input.getOrDefault(key, "")); }
        catch (DateTimeParseException ex) { throw invalid("Enter a valid date for " + key + "."); }
    }
    private static LocalTime time(Map<String, String> input, String key) {
        try { return LocalTime.parse(input.getOrDefault(key, "")); }
        catch (DateTimeParseException ex) { throw invalid("Enter a valid time for " + key + "."); }
    }
    private static String required(Map<String, String> input, String key, int max) {
        String value = optional(input, key, max); require(!value.isBlank(), "Please enter " + key + "."); return value;
    }
    private static String optional(Map<String, String> input, String key, int max) {
        String value = input.getOrDefault(key, "").strip();
        require(value.length() <= max, key + " must be at most " + max + " characters."); return value;
    }
    private static void sameEvent(long expected, long actual) { if (expected != actual) throw notFound(); }
    private static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found in this event."); }
    public static class OverlapException extends IllegalArgumentException {
        public OverlapException(String conflicts) { super("Overlapping assignments: " + conflicts); }
    }

    public record Snapshot(Event event, List<Volunteer> volunteers, List<Department> departments,
                           List<Shift> shifts, List<Assignment> assignments) {
        public List<Volunteer> availableVolunteers(Shift shift) {
            Set<Long> busy = new HashSet<>();
            assignments.stream().filter(a -> overlaps(a.getShift(), shift.getDate(), shift.getStartTime(), shift.getEndTime()))
                    .forEach(a -> busy.add(a.getVolunteer().getId()));
            return volunteers.stream().filter(v -> !busy.contains(v.getId()))
                    .filter(v -> v.getDepartmentRoles().keySet().stream()
                            .anyMatch(d -> d.getId().equals(shift.getDepartment().getId()))).toList();
        }

        public List<ShiftRow> rows() {
            return shifts.stream().map(s -> new ShiftRow(s, assignments.stream()
                    .filter(a -> a.getShift().getId().equals(s.getId())).toList())).toList();
        }
    }
    public record ShiftRow(Shift shift, List<Assignment> assignments) {
        public int getMissing() { return Math.max(0, shift.getRequiredCount() - assignments.size()); }
        public String getStatus() {
            return assignments.size() < shift.getRequiredCount() ? "Underfilled"
                    : assignments.size() == shift.getRequiredCount() ? "Filled" : "Overfilled";
        }
        public String getStatusClass() { return getMissing() > 0 ? "warning" : "success"; }
    }
}
