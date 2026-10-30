package org.volunteers.scheduler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.repository.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class BackupService {
    private final SchedulerService scheduler;
    private final Events events;
    private final Departments departments;
    private final Volunteers volunteers;
    private final Shifts shifts;
    private final Assignments assignments;
    private final ObjectMapper json;
    public BackupService(SchedulerService scheduler, Events events, Departments departments, Volunteers volunteers,
                         Shifts shifts, Assignments assignments, ObjectMapper json) {
        this.scheduler = scheduler; this.events = events; this.departments = departments; this.volunteers = volunteers;
        this.shifts = shifts; this.assignments = assignments; this.json = json;
    }
    public record Backup(long eventId, String token, Map<String, byte[]> files, Map<String, Integer> counts) implements java.io.Serializable {}
    private static final Map<String, List<String>> HEADERS = Map.of(
        "departments", List.of("id", "name", "description"),
        "volunteers", List.of("id", "name", "phone", "email", "congregation", "notes", "privilege", "departmentRoles", "eventAdministrator"),
        "shifts", List.of("id", "departmentId", "name", "date", "startTime", "endTime", "requiredCount", "notes", "volunteerIds"));
    public static boolean supports(String type) { return HEADERS.containsKey(type); }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Backup capture(long eventId) {
        var data = scheduler.snapshot(eventId);
        String token = UUID.randomUUID().toString();
        Map<String, List<List<String>>> rows = new HashMap<>();
        HEADERS.keySet().forEach(k -> rows.put(k, new ArrayList<>()));
        for (Department d : data.departments()) rows.get("departments").add(values(d.getId(), d.getName(), d.getDescription()));
        for (Volunteer v : data.volunteers()) {
            Map<String, String> roles = new TreeMap<>();
            v.getDepartmentRoles().forEach((d, r) -> roles.put(d.getId().toString(), r.name()));
            rows.get("volunteers").add(values(v.getId(), v.getName(), v.getPhone(), v.getEmail(), v.getCongregation(), v.getNotes(), v.getPrivilege(), encode(roles), v.isEventAdministrator()));
        }
        for (var r : data.rows()) {
            Shift s = r.shift();
            rows.get("shifts").add(values(s.getId(), s.getDepartment().getId(), s.getName(), s.getDate(), s.getStartTime(), s.getEndTime(),
                s.getRequiredCount(), s.getNotes(), encode(r.assignments().stream().map(a -> a.getVolunteer().getId().toString()).toList())));
        }
        Map<String, byte[]> files = new HashMap<>(); Map<String, Integer> counts = new HashMap<>();
        rows.forEach((type, records) -> {
            StringBuilder out = new StringBuilder("\ufeff");
            line(out, List.of("scheduler-backup", "2", token, type)); line(out, HEADERS.get(type));
            records.forEach(row -> line(out, row));
            files.put(type, out.toString().getBytes(StandardCharsets.UTF_8)); counts.put(type, records.size());
        });
        return new Backup(eventId, token, files, counts);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException("Cannot encode backup.", ex); }
    }
    private static List<String> values(Object... values) { return Arrays.stream(values).map(v -> Objects.toString(v, "")).toList(); }
    private static void line(StringBuilder out, List<String> cells) {
        StringJoiner row = new StringJoiner(",");
        for (String cell : cells) {
            // Escape leading apostrophes too, making formula protection fully reversible.
            if (cell.startsWith("'") || cell.stripLeading().matches("(?s)^[=+@\\-\\t\\r\\n].*")) cell = "'" + cell;
            row.add("\"" + cell.replace("\"", "\"\"") + "\"");
        }
        out.append(row).append("\r\n");
    }
    static List<List<String>> parse(byte[] bytes) {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (Exception ex) { throw new IllegalArgumentException("File must be UTF-8 CSV."); }
        if (text.startsWith("\ufeff")) text = text.substring(1);
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i++) != '"') throw new IllegalArgumentException("Expected quoted CSV cell at row " + (rows.size() + 1));
            StringBuilder cell = new StringBuilder(); boolean closed = false;
            while (i < text.length()) {
                char c = text.charAt(i++);
                if (c == '"') {
                    if (i < text.length() && text.charAt(i) == '"') { cell.append('"'); i++; }
                    else { closed = true; break; }
                } else cell.append(c);
            }
            if (!closed) throw new IllegalArgumentException("Unclosed CSV cell.");
            String value = cell.toString(); row.add(value.startsWith("'") ? value.substring(1) : value);
            if (i == text.length()) { rows.add(row); break; }
            char separator = text.charAt(i++);
            if (separator == ',') { if (i == text.length()) throw new IllegalArgumentException("Missing CSV cell."); continue; }
            if (separator == '\r' && i < text.length() && text.charAt(i) == '\n') i++;
            else if (separator != '\n') throw new IllegalArgumentException("Invalid CSV separator.");
            rows.add(row); row = new ArrayList<>();
        }
        return rows;
    }
    @Transactional
    public Map<String, Integer> restore(long eventId, Map<String, byte[]> files) {
        Event event = events.lockForVolunteerUpdate(eventId).orElseThrow(() -> new IllegalArgumentException("Event not found."));
        Map<String, List<List<String>>> tables = new HashMap<>(); String token = null;
        for (String type : List.of("departments", "volunteers", "shifts")) {
            try {
                byte[] bytes = files.get(type);
                if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("File is required.");
                var rows = parse(bytes);
                if (rows.size() < 2 || rows.get(0).size() != 4 || !rows.get(0).get(0).equals("scheduler-backup")
                    || !Set.of("1", "2").contains(rows.get(0).get(1)) || !rows.get(0).get(3).equals(type))
                    throw new IllegalArgumentException("Unsupported backup format or wrong file category.");
                boolean legacy = rows.get(0).get(1).equals("1");
                List<String> expected = legacy && type.equals("volunteers") ? HEADERS.get(type).subList(0, 8) : HEADERS.get(type);
                if (!rows.get(1).equals(expected)) throw new IllegalArgumentException("Unsupported backup columns.");
                if (legacy && type.equals("volunteers")) {
                    for (int i = 2; i < rows.size(); i++) {
                        if (rows.get(i).size() != 8) throw new IllegalArgumentException("Wrong column count.");
                        rows.get(i).add("false");
                    }
                }
                UUID.fromString(rows.get(0).get(2));
                if (token != null && !token.equals(rows.get(0).get(2))) throw new IllegalArgumentException("Files must belong to the same backup snapshot.");
                token = rows.get(0).get(2);
                tables.put(type, rows.subList(2, rows.size()));
            } catch (IllegalArgumentException ex) { throw new IllegalArgumentException(type + ": " + ex.getMessage()); }
        }
        Map<String, Department> ds = new LinkedHashMap<>(); Map<String, Volunteer> vs = new LinkedHashMap<>(); Map<String, Shift> ss = new LinkedHashMap<>();
        List<Assignment> restoredAssignments = new ArrayList<>();
        validateRows("departments", tables, r -> {
            Department d = new Department(); d.setEvent(event); d.setName(field(r.get(1), 160, true)); d.setDescription(field(r.get(2), 2000, false)); put(ds, r.get(0), d);
        });
        validateRows("volunteers", tables, r -> {
            Volunteer v = new Volunteer(); v.setEvent(event); v.setName(field(r.get(1), 160, true)); v.setPhone(field(r.get(2), 60, false));
            v.setEmail(field(r.get(3), 254, false)); v.setCongregation(field(r.get(4), 160, false)); v.setNotes(field(r.get(5), 2000, false));
            v.setPrivilege(r.get(6).isEmpty() ? null : Volunteer.Privilege.valueOf(r.get(6)));
            Map<String, String> roles = json.readerFor(new TypeReference<Map<String, String>>() {})
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(r.get(7));
            if (!Set.of("true", "false").contains(r.get(8))) throw new IllegalArgumentException("Invalid administrator flag.");
            v.setEventAdministrator(Boolean.parseBoolean(r.get(8)));
            for (var role : roles.entrySet()) {
                Volunteer.Role value = Volunteer.Role.valueOf(role.getValue());
                if (value == Volunteer.Role.ADMIN) { v.setEventAdministrator(true); value = Volunteer.Role.VOLUNTEER; }
                v.getDepartmentRoles().put(reference(ds, role.getKey()), value);
            }
            if (v.isEventAdministrator() && PhoneAccess.normalize(v.getPhone()).isEmpty()) throw new IllegalArgumentException("Administrators need a valid phone number.");
            put(vs, r.get(0), v);
        });
        validateRows("shifts", tables, r -> {
            Shift s = new Shift(); s.setDepartment(reference(ds, r.get(1))); s.setName(field(r.get(2), 160, true)); s.setDate(LocalDate.parse(r.get(3)));
            s.setStartTime(LocalTime.parse(r.get(4))); s.setEndTime(LocalTime.parse(r.get(5))); s.setRequiredCount(Integer.parseInt(r.get(6))); s.setNotes(field(r.get(7), 2000, false));
            if (s.getDate().isBefore(event.getStartDate()) || s.getDate().isAfter(event.getEndDate()) || !s.getEndTime().isAfter(s.getStartTime())
                || s.getRequiredCount() < 1 || s.getRequiredCount() > 10000) throw new IllegalArgumentException("Invalid shift dates, times, or staffing count.");
            put(ss, r.get(0), s);
            List<String> ids = json.readerFor(new TypeReference<List<String>>() {})
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(r.get(8));
            Set<String> seen = new HashSet<>();
            for (String id : ids) {
                if (!seen.add(id)) throw new IllegalArgumentException("Duplicate assignment.");
                Assignment a = new Assignment(); a.setShift(s); a.setVolunteer(reference(vs, id)); restoredAssignments.add(a);
            }
        });
        if (vs.values().stream().noneMatch(Volunteer::isEventAdministrator)) throw new IllegalArgumentException(SchedulerService.LAST_ADMIN);
        assignments.deleteAll(assignments.findByShiftDepartmentEventIdOrderByIdAsc(eventId)); assignments.flush();
        shifts.deleteAll(shifts.findByDepartmentEventIdOrderByDateAscStartTimeAscIdAsc(eventId)); shifts.flush();
        volunteers.deleteAll(volunteers.findByEventIdOrderByNameAscIdAsc(eventId)); volunteers.flush();
        departments.deleteAll(departments.findByEventIdOrderByNameAscIdAsc(eventId)); departments.flush();
        departments.saveAllAndFlush(ds.values()); volunteers.saveAllAndFlush(vs.values()); shifts.saveAllAndFlush(ss.values()); assignments.saveAllAndFlush(restoredAssignments);
        return Map.of("departments", ds.size(), "volunteers", vs.size(), "shifts", ss.size(), "assignments", restoredAssignments.size());
    }
    private interface RowReader { void read(List<String> row) throws Exception; }
    private void validateRows(String type, Map<String, List<List<String>>> tables, RowReader reader) {
        int row = 2;
        for (var values : tables.get(type)) {
            row++;
            try {
                if (values.size() != HEADERS.get(type).size()) throw new IllegalArgumentException("Wrong column count.");
                reader.read(values);
            } catch (Exception ex) { throw new IllegalArgumentException(type + " row " + row + ": " + Objects.toString(ex.getMessage(), "Invalid data."), ex); }
        }
    }
    private static String field(String value, int limit, boolean required) {
        if (value.length() > limit || required && value.isBlank()) throw new IllegalArgumentException("Missing or oversized field."); return value;
    }
    private static <T> void put(Map<String, T> map, String id, T value) {
        if (!id.matches("[1-9][0-9]*") || map.putIfAbsent(id, value) != null) throw new IllegalArgumentException("Invalid or duplicate identifier: " + id);
    }
    private static <T> T reference(Map<String, T> map, String id) {
        T value = map.get(id); if (value == null) throw new IllegalArgumentException("Unknown reference: " + id); return value;
    }
}
