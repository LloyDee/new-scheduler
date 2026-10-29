package org.volunteers.scheduler;

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
        sameEvent(eventId, v.event.id); return v;
    }
    public Department department(long eventId, long id) {
        Department d = departments.findById(id).orElseThrow(SchedulerService::notFound);
        sameEvent(eventId, d.event.id); return d;
    }
    public Shift shift(long eventId, long id) {
        Shift s = shifts.findById(id).orElseThrow(SchedulerService::notFound);
        sameEvent(eventId, s.department.event.id); return s;
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
                    .noneMatch(s -> s.date.isBefore(start) || s.date.isAfter(end)),
                    "These dates exclude existing shifts. Move or delete those shifts first.");
        }
        e.name = name; e.type = type; e.startDate = start; e.endDate = end; e.venue = venue;
        e.assembly = assembly;
        return events.save(e);
    }

    @Transactional
    public Volunteer saveVolunteer(long eventId, Long id, Map<String, String> input) {
        Event e = events.lockForVolunteerUpdate(eventId).orElseThrow(SchedulerService::notFound);
        String name = required(input, "name", 160), phone = required(input, "phone", 60);
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
            catch (IllegalArgumentException ex) { throw invalid("Choose a valid role for " + selected.name + "."); }
            departmentRoles.put(selected, role);
        }
        require(!departmentRoles.isEmpty(), "Choose at least one department and its role.");
        List<Volunteer> existing = volunteers.findByEventIdOrderByNameAscIdAsc(eventId);
        for (var membership : departmentRoles.entrySet()) {
            Volunteer.Role role = membership.getValue();
            int limit = role == Volunteer.Role.OVERSEER ? 1 : role == Volunteer.Role.ASSISTANT_OVERSEER ? 2 : Integer.MAX_VALUE;
            long count = existing.stream().filter(other -> !Objects.equals(other.id, id))
                    .filter(other -> other.departmentRoles.entrySet().stream().anyMatch(entry ->
                            entry.getKey().id.equals(membership.getKey().id) && entry.getValue() == role)).count();
            require(count < limit, membership.getKey().name + " allows at most " + limit + " "
                    + role.getLabel() + (limit == 1 ? "." : "s."));
        }
        Volunteer v = id == null ? new Volunteer() : volunteer(eventId, id);
        v.event = e; v.name = name; v.phone = phone; v.email = email; v.congregation = congregation; v.notes = notes;
        v.privilege = privilege;
        v.departmentRoles.clear();
        v.departmentRoles.putAll(departmentRoles);
        return volunteers.save(v);
    }

    @Transactional
    public Department saveDepartment(long eventId, Long id, Map<String, String> input) {
        Event e = event(eventId);
        String name = required(input, "name", 160), description = optional(input, "description", 2000);
        Department d = id == null ? new Department() : department(eventId, id);
        d.event = e; d.name = name; d.description = description;
        return departments.save(d);
    }

    @Transactional
    public Shift saveShift(long eventId, Long id, Map<String, String> input) {
        Event e = event(eventId);
        Department d = department(eventId, number(input, "departmentId"));
        LocalDate date = e.type == Event.Type.CIRCUIT_ASSEMBLY ? e.startDate : date(input, "date");
        LocalTime start = time(input, "startTime"), end = time(input, "endTime");
        if (e.type == Event.Type.CIRCUIT_ASSEMBLY) {
            for (LocalTime value : List.of(start, end)) {
                require(!value.isBefore(LocalTime.of(6, 0)) && !value.isAfter(LocalTime.of(17, 30)),
                        "Shift times must be between 06:00 AM and 05:30 PM.");
                require(value.getMinute() % 10 == 0 && value.getSecond() == 0 && value.getNano() == 0,
                        "Shift times must use 10-minute increments.");
            }
        }
        require(!date.isBefore(e.startDate) && !date.isAfter(e.endDate), "Shift date must be within the event dates.");
        require(end.isAfter(start), "End time must be after start time. Overnight shifts are not supported.");
        long required = number(input, "requiredCount");
        require(required >= 1 && required <= 10000, "Required volunteers must be between 1 and 10,000.");
        String notes = optional(input, "notes", 2000);
        String name = required(input, "name", 160);
        Shift s = id == null ? new Shift() : shift(eventId, id);
        if (id != null && !(date.equals(s.date) && start.equals(s.startTime) && end.equals(s.endTime))) {
            List<Assignment> all = assignments.findByShiftDepartmentEventIdOrderByIdAsc(eventId);
            Set<Long> assigned = new HashSet<>();
            all.stream().filter(a -> a.shift.id.equals(id)).forEach(a -> assigned.add(a.volunteer.id));
            List<String> conflicts = all.stream().filter(a -> assigned.contains(a.volunteer.id) && !a.shift.id.equals(id)
                    && overlaps(a.shift, date, start, end)).map(SchedulerService::conflictDescription).toList();
            if (!conflicts.isEmpty() && !confirmed(input)) throw new OverlapException(String.join("; ", conflicts));
        }
        s.name = name; s.department = d; s.date = date; s.startTime = start; s.endTime = end;
        s.requiredCount = (int) required; s.notes = notes;
        return shifts.save(s);
    }

    @Transactional
    public void assign(long eventId, long shiftId, long volunteerId, boolean confirmed) {
        Shift s = shift(eventId, shiftId);
        Volunteer v = volunteer(eventId, volunteerId);
        require(!assignments.existsByShiftIdAndVolunteerId(shiftId, volunteerId), "This volunteer is already assigned to this shift.");
        List<String> conflicts = assignments.findByShiftDepartmentEventIdOrderByIdAsc(eventId).stream()
                .filter(a -> a.volunteer.id.equals(volunteerId) && overlaps(a.shift, s.date, s.startTime, s.endTime))
                .map(SchedulerService::conflictDescription).toList();
        if (!conflicts.isEmpty() && !confirmed) throw new OverlapException(String.join("; ", conflicts));
        Assignment a = new Assignment(); a.shift = s; a.volunteer = v; assignments.saveAndFlush(a);
    }

    @Transactional
    public void unassign(long eventId, long id) {
        Assignment a = assignments.findById(id).orElseThrow(SchedulerService::notFound);
        sameEvent(eventId, a.shift.department.event.id); assignments.delete(a);
    }

    @Transactional
    public void deleteVolunteer(long eventId, long id) {
        Volunteer v = volunteer(eventId, id);
        assignments.deleteAll(assignments.findByShiftDepartmentEventIdOrderByIdAsc(eventId).stream()
                .filter(a -> a.volunteer.id.equals(id)).toList());
        assignments.flush(); volunteers.delete(v);
    }

    @Transactional
    public void deleteShift(long eventId, long id) {
        Shift s = shift(eventId, id);
        assignments.deleteAll(assignments.findByShiftDepartmentEventIdOrderByIdAsc(eventId).stream()
                .filter(a -> a.shift.id.equals(id)).toList());
        assignments.flush(); shifts.delete(s); shifts.flush();
    }

    @Transactional
    public void deleteDepartment(long eventId, long id) {
        Department d = department(eventId, id);
        volunteers.findByEventIdOrderByNameAscIdAsc(eventId).forEach(v ->
                v.departmentRoles.keySet().removeIf(selected -> selected.id.equals(id)));
        volunteers.flush();
        shifts.findByDepartmentEventIdOrderByDateAscStartTimeAscIdAsc(eventId).stream()
                .filter(s -> s.department.id.equals(id)).toList().forEach(s -> deleteShift(eventId, s.id));
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
        return s.date.equals(date) && start.isBefore(s.endTime) && end.isAfter(s.startTime);
    }
    private static String conflictDescription(Assignment a) {
        return a.volunteer.name + " — " + a.shift.department.name + ", " + a.shift.date + " "
                + a.shift.startTime + "–" + a.shift.endTime;
    }
    static boolean confirmed(Map<String, String> input) { return "true".equals(input.get("confirmOverlap")); }
    static long number(Map<String, String> input, String key) {
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
            assignments.stream().filter(a -> overlaps(a.shift, shift.date, shift.startTime, shift.endTime))
                    .forEach(a -> busy.add(a.volunteer.id));
            return volunteers.stream().filter(v -> !busy.contains(v.id))
                    .filter(v -> v.departmentRoles.keySet().stream()
                            .anyMatch(d -> d.id.equals(shift.department.id))).toList();
        }

        public List<ShiftRow> rows() {
            return shifts.stream().map(s -> new ShiftRow(s, assignments.stream()
                    .filter(a -> a.shift.id.equals(s.id)).toList())).toList();
        }
    }
    public record ShiftRow(Shift shift, List<Assignment> assignments) {
        public int getMissing() { return Math.max(0, shift.requiredCount - assignments.size()); }
        public String getStatus() {
            return assignments.size() < shift.requiredCount ? "Underfilled"
                    : assignments.size() == shift.requiredCount ? "Filled" : "Overfilled";
        }
        public String getStatusClass() { return getMissing() > 0 ? "warning" : "success"; }
    }
}
