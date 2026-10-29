package org.volunteers.scheduler;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebController {
    private final SchedulerService service;
    public WebController(SchedulerService service) { this.service = service; }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalidRequest(Model model) {
        model.addAttribute("status", 400); return "error";
    }

    @ModelAttribute
    void common(Model model, HttpSession session,
                @CookieValue(name = "schedulerTimeFormat", defaultValue = "12") String format) {
        model.addAttribute("event", null);
        Object id = session.getAttribute("activeEvent");
        if (id instanceof Long eventId) {
            try { model.addAttribute("event", service.event(eventId)); }
            catch (org.springframework.web.server.ResponseStatusException ex) { session.removeAttribute("activeEvent"); }
        }
        model.addAttribute("timeFormat", "24".equals(format) ? "24" : "12");
        model.addAttribute("timePattern", "24".equals(format) ? "HH:mm" : "h:mm a");
    }

    @GetMapping("/")
    String index(HttpSession session) {
        Long id = (Long) session.getAttribute("activeEvent");
        return id == null ? "home" : "redirect:/events/" + id + "/home";
    }

    @GetMapping("/events")
    String events(@RequestParam(required = false) Long edit,
                  @RequestParam(defaultValue = "false") boolean create, Model model) {
        model.addAttribute("events", service.events()); model.addAttribute("types", Event.Type.values());
        Map<String, String> defaults = new HashMap<>();
        if (edit != null) {
            Event e = service.event(edit);
            defaults.putAll(Map.of("name", e.name, "type", e.type.name(), "startDate", e.startDate.toString(),
                    "endDate", e.endDate.toString(), "venue", e.venue));
            defaults.put("assembly", e.assembly == null ? "" : e.assembly);
        }
        draft(model, defaults); model.addAttribute("editId", edit);
        model.addAttribute("showEditor", create || edit != null || model.containsAttribute("error"));
        return "events";
    }

    @PostMapping("/events/save")
    String saveEvent(@RequestParam(required = false) Long id, @RequestParam Map<String, String> input,
                     RedirectAttributes flash, HttpSession session) {
        return attempt(() -> {
            Event e = service.saveEvent(id, input); session.setAttribute("activeEvent", e.id);
            return "/events/" + e.id + "/home";
        }, id == null ? "/events?create=true" : "/events" + editQuery(id), input, flash, "Event saved.");
    }

    @PostMapping("/events/{id}/select")
    String select(@PathVariable long id, HttpSession session) {
        service.event(id); session.setAttribute("activeEvent", id); return "redirect:/events/" + id + "/home";
    }

    @PostMapping("/events/{id}/delete")
    String deleteEvent(@PathVariable long id, HttpSession session, RedirectAttributes flash) {
        service.deleteEvent(id);
        if (Objects.equals(session.getAttribute("activeEvent"), id)) session.removeAttribute("activeEvent");
        flash.addFlashAttribute("notice", "Event and its data deleted."); return "redirect:/events";
    }

    @GetMapping("/events/{eventId}/home")
    String home(@PathVariable long eventId, Model model, HttpSession session) {
        var data = prepare(eventId, model, session);
        model.addAttribute("missing", data.rows().stream().mapToInt(SchedulerService.ShiftRow::getMissing).sum());
        LocalDateTime now = LocalDateTime.now();
        model.addAttribute("upcoming", data.rows().stream().filter(r ->
                        LocalDateTime.of(r.shift().date, r.shift().endTime).isAfter(now))
                .limit(5).toList());
        return "home";
    }

    @GetMapping("/events/{eventId}/volunteers")
    String volunteers(@PathVariable long eventId, @RequestParam(defaultValue = "") String q,
                      @RequestParam(required = false) Long departmentId,
                      @RequestParam(required = false) Long edit, @RequestParam(required = false) Long view,
                      @RequestParam(defaultValue = "false") boolean create,
                      Model model, HttpSession session) {
        var data = prepare(eventId, model, session);
        String query = q.strip().toLowerCase(Locale.ROOT);
        model.addAttribute("q", q);
        model.addAttribute("departmentId", departmentId);
        model.addAttribute("filteredVolunteers", data.volunteers().stream()
                .filter(v -> departmentId == null || v.departmentRoles.keySet().stream()
                        .anyMatch(d -> d.id.equals(departmentId))).filter(v ->
                (v.name + " " + v.congregation + " " + v.phone + " " + v.email).toLowerCase(Locale.ROOT).contains(query)).toList());
        Map<String, String> defaults = new HashMap<>();
        if (edit != null) {
            Volunteer v = service.volunteer(eventId, edit);
            defaults.putAll(Map.of("name", v.name, "phone", v.phone, "email", v.email,
                    "congregation", v.congregation, "notes", v.notes));
            defaults.put("privilege", v.privilege == null ? "" : v.privilege.name());
            v.departmentRoles.forEach((department, role) -> defaults.put("departmentRole_" + department.id, role.name()));
        }
        if (view != null) {
            model.addAttribute("selectedVolunteer", service.volunteer(eventId, view));
            model.addAttribute("personalRows", data.rows().stream().filter(r -> r.assignments().stream()
                    .anyMatch(a -> a.volunteer.id.equals(view))).toList());
        }
        draft(model, defaults); model.addAttribute("editId", edit);
        model.addAttribute("showEditor", create || edit != null || model.containsAttribute("error") && model.containsAttribute("draft"));
        model.addAttribute("volunteerRoles", Volunteer.Role.values());
        model.addAttribute("volunteerPrivileges", Volunteer.Privilege.values());
        return "volunteers";
    }

    @PostMapping("/events/{eventId}/volunteers/save")
    String saveVolunteer(@PathVariable long eventId, @RequestParam(required = false) Long id,
                         @RequestParam Map<String, String> input, RedirectAttributes flash) {
        String base = "/events/" + eventId + "/volunteers";
        return attempt(() -> { service.saveVolunteer(eventId, id, input); return base; },
                base + editQuery(id) + "#editor", input, flash, "Volunteer saved.");
    }

    @PostMapping("/events/{eventId}/volunteers/{id}/delete")
    String deleteVolunteer(@PathVariable long eventId, @PathVariable long id, RedirectAttributes flash) {
        service.deleteVolunteer(eventId, id); flash.addFlashAttribute("notice", "Volunteer and their assignments deleted.");
        return "redirect:/events/" + eventId + "/volunteers";
    }

    @GetMapping("/events/{eventId}/departments")
    String departments(@PathVariable long eventId, @RequestParam(required = false) Long edit,
                       @RequestParam(required = false) Long view, @RequestParam(defaultValue = "false") boolean create,
                       Model model, HttpSession session) {
        var data = prepare(eventId, model, session);
        Map<String, String> defaults = new HashMap<>();
        if (edit != null) {
            Department d = service.department(eventId, edit);
            defaults.putAll(Map.of("name", d.name, "description", d.description));
        }
        Map<Long, Map<String, Object>> departmentDetails = new HashMap<>();
        for (Department d : data.departments()) {
            List<Volunteer> members = data.volunteers().stream()
                    .filter(v -> v.departmentRoles.keySet().stream().anyMatch(dept -> dept.id.equals(d.id))).toList();
            Map<String, Object> details = new HashMap<>();
            details.put("volunteerCount", members.size());
            for (Volunteer.Role role : List.of(Volunteer.Role.OVERSEER, Volunteer.Role.ASSISTANT_OVERSEER)) {
                List<String> names = members.stream()
                        .filter(v -> v.departmentRoles.entrySet().stream()
                                .anyMatch(entry -> entry.getKey().id.equals(d.id) && entry.getValue() == role))
                        .map(Volunteer::getName).toList();
                details.put(role.name(), names.isEmpty() ? "Not assigned" : String.join(", ", names));
            }
            departmentDetails.put(d.id, details);
        }
        model.addAttribute("departmentDetails", departmentDetails);
        if (view != null) {
            model.addAttribute("selectedDepartment", service.department(eventId, view));
            model.addAttribute("departmentRows", data.rows().stream().filter(r -> r.shift().department.id.equals(view)).toList());
        }
        draft(model, defaults); model.addAttribute("editId", edit);
        model.addAttribute("showEditor", create || edit != null || model.containsAttribute("error") && model.containsAttribute("draft"));
        return "departments";
    }

    @PostMapping("/events/{eventId}/departments/save")
    String saveDepartment(@PathVariable long eventId, @RequestParam(required = false) Long id,
                          @RequestParam Map<String, String> input, RedirectAttributes flash) {
        String base = "/events/" + eventId + "/departments";
        return attempt(() -> { service.saveDepartment(eventId, id, input); return base; },
                base + editQuery(id) + "#editor", input, flash, "Department saved.");
    }

    @PostMapping("/events/{eventId}/departments/{id}/delete")
    String deleteDepartment(@PathVariable long eventId, @PathVariable long id, RedirectAttributes flash) {
        service.deleteDepartment(eventId, id); flash.addFlashAttribute("notice", "Department, shifts, and assignments deleted.");
        return "redirect:/events/" + eventId + "/departments";
    }

    @GetMapping("/events/{eventId}/schedule")
    String schedule(@PathVariable long eventId, @RequestParam(required = false) Long edit,
                    @RequestParam(defaultValue = "false") boolean create,
                    @RequestParam(required = false) Long departmentId, @RequestParam(required = false) LocalDate date,
                    Model model, HttpSession session) {
        var data = prepare(eventId, model, session);
        filter(data, departmentId, date, model);
        Map<Long, List<Volunteer>> assignmentOptions = new HashMap<>();
        data.shifts().forEach(s -> assignmentOptions.put(s.id, data.availableVolunteers(s)));
        model.addAttribute("assignmentOptions", assignmentOptions);
        Map<String, String> defaults = new HashMap<>(Map.of("date", data.event().startDate.toString(), "requiredCount", "1"));
        if (departmentId != null) defaults.put("departmentId", departmentId.toString());
        if (edit != null) {
            Shift s = service.shift(eventId, edit);
            defaults.putAll(Map.of("name", s.getName(), "departmentId", s.department.id.toString(), "date", s.date.toString(),
                    "startTime", s.startTime.toString(), "endTime", s.endTime.toString(),
                    "requiredCount", Integer.toString(s.requiredCount), "notes", s.notes));
        }
        draft(model, defaults); model.addAttribute("editId", edit);
        model.addAttribute("shiftTimes", java.util.stream.IntStream.rangeClosed(0, 69)
                .mapToObj(i -> java.time.LocalTime.of(6, 0).plusMinutes(i * 10L)).toList());
        model.addAttribute("showEditor", create || edit != null || model.containsAttribute("error") && model.containsAttribute("draft"));
        return "schedule";
    }

    @PostMapping("/events/{eventId}/schedule/save")
    String saveShift(@PathVariable long eventId, @RequestParam(required = false) Long id,
                     @RequestParam Map<String, String> input, RedirectAttributes flash) {
        String base = "/events/" + eventId + "/schedule";
        return attempt(() -> { service.saveShift(eventId, id, input); return base; },
                base + editQuery(id) + "#editor", input, flash, "Shift saved.");
    }

    @PostMapping("/events/{eventId}/schedule/{id}/delete")
    String deleteShift(@PathVariable long eventId, @PathVariable long id, RedirectAttributes flash) {
        service.deleteShift(eventId, id); flash.addFlashAttribute("notice", "Shift and its assignments deleted.");
        return "redirect:/events/" + eventId + "/schedule";
    }

    @PostMapping("/events/{eventId}/schedule/{id}/assign")
    String assign(@PathVariable long eventId, @PathVariable long id, @RequestParam Map<String, String> input,
                  RedirectAttributes flash) {
        String base = "/events/" + eventId + "/schedule";
        try {
            service.assign(eventId, id, SchedulerService.number(input, "volunteerId"), SchedulerService.confirmed(input));
            flash.addFlashAttribute("notice", "Volunteer assigned.");
        } catch (SchedulerService.OverlapException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            flash.addFlashAttribute("pending", Map.of("shiftId", Long.toString(id), "volunteerId", input.get("volunteerId")));
        } catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:" + base + "#shift-" + id;
    }

    @PostMapping("/events/{eventId}/assignments/{id}/delete")
    String unassign(@PathVariable long eventId, @PathVariable long id, RedirectAttributes flash) {
        service.unassign(eventId, id); flash.addFlashAttribute("notice", "Assignment removed.");
        return "redirect:/events/" + eventId + "/schedule";
    }

    @GetMapping("/events/{eventId}/more")
    String more(@PathVariable long eventId, Model model, HttpSession session) {
        prepare(eventId, model, session); return "more";
    }

    @GetMapping("/settings")
    String settings() { return "settings"; }

    @PostMapping("/settings")
    String settings(@RequestParam(defaultValue = "12") String timeFormat, HttpServletResponse response,
                    RedirectAttributes flash) {
        Cookie cookie = new Cookie("schedulerTimeFormat", "24".equals(timeFormat) ? "24" : "12");
        cookie.setPath("/"); cookie.setHttpOnly(true); cookie.setMaxAge(365 * 24 * 60 * 60); cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie); flash.addFlashAttribute("notice", "Display preferences saved.");
        return "redirect:/settings";
    }

    @GetMapping("/events/{eventId}/reports")
    String reports(@PathVariable long eventId, @RequestParam(defaultValue = "department") String type,
                   @RequestParam(required = false) Long departmentId, @RequestParam(required = false) LocalDate date,
                   Model model, HttpSession session) {
        var data = prepare(eventId, model, session);
        var rows = filter(data, departmentId, date, model);
        model.addAttribute("reportType", reportType(type));
        model.addAttribute("volunteerRows", volunteerReport(data, rows, departmentId != null || date != null));
        return "reports";
    }

    @GetMapping("/events/{eventId}/reports.csv")
    ResponseEntity<byte[]> csv(@PathVariable long eventId, @RequestParam(defaultValue = "department") String type,
                               @RequestParam(required = false) Long departmentId, @RequestParam(required = false) LocalDate date) {
        var data = service.snapshot(eventId); String selected = reportType(type);
        List<SchedulerService.ShiftRow> rows = filtered(data, departmentId, date);
        StringBuilder out = new StringBuilder("\ufeff");
        if (selected.equals("volunteer")) {
            csvLine(out, "Volunteer", "Congregation", "Department", "Shift", "Date", "Start", "End");
            for (VolunteerReport v : volunteerReport(data, rows, departmentId != null || date != null)) {
                if (v.assignments().isEmpty()) csvLine(out, v.volunteer().name, v.volunteer().congregation, "Unassigned", "", "", "", "");
                for (Assignment a : v.assignments()) csvLine(out, v.volunteer().name, v.volunteer().congregation,
                        a.shift.department.name, a.shift.getName(), a.shift.date, a.shift.startTime, a.shift.endTime);
            }
        } else {
            csvLine(out, "Department", "Shift", "Date", "Start", "End", "Required", "Assigned", "Missing", "Status", "Volunteers");
            for (var r : rows) csvLine(out, r.shift().department.name, r.shift().getName(), r.shift().date, r.shift().startTime,
                    r.shift().endTime, r.shift().requiredCount, r.assignments().size(), r.getMissing(), r.getStatus(),
                    String.join("; ", r.assignments().stream().map(a -> a.volunteer.name).toList()));
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"event-" + eventId + "-" + selected + ".csv\"")
                .body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private SchedulerService.Snapshot prepare(long eventId, Model model, HttpSession session) {
        var data = service.snapshot(eventId); session.setAttribute("activeEvent", eventId);
        model.addAttribute("event", data.event()); model.addAttribute("base", "/events/" + eventId);
        model.addAttribute("volunteers", data.volunteers()); model.addAttribute("departments", data.departments());
        model.addAttribute("allRows", data.rows()); return data;
    }
    private List<SchedulerService.ShiftRow> filter(SchedulerService.Snapshot data, Long departmentId, LocalDate date, Model model) {
        var rows = filtered(data, departmentId, date);
        model.addAttribute("rows", rows); model.addAttribute("departmentId", departmentId); model.addAttribute("date", date); return rows;
    }
    private List<SchedulerService.ShiftRow> filtered(SchedulerService.Snapshot data, Long departmentId, LocalDate date) {
        if (departmentId != null) service.department(data.event().id, departmentId);
        return data.rows().stream().filter(r -> departmentId == null || r.shift().department.id.equals(departmentId))
                .filter(r -> date == null || r.shift().date.equals(date)).toList();
    }
    private static List<VolunteerReport> volunteerReport(SchedulerService.Snapshot data, List<SchedulerService.ShiftRow> rows, boolean filtered) {
        List<Assignment> selected = rows.stream().flatMap(r -> r.assignments().stream()).toList();
        return data.volunteers().stream().map(v -> new VolunteerReport(v, selected.stream()
                .filter(a -> a.volunteer.id.equals(v.id)).toList())).filter(v -> !filtered || !v.assignments().isEmpty()).toList();
    }
    private static String reportType(String value) {
        if (!Set.of("department", "volunteer", "coverage").contains(value)) throw new IllegalArgumentException("Unknown report type.");
        return value;
    }
    static void csvLine(StringBuilder out, Object... values) {
        StringJoiner line = new StringJoiner(",");
        for (Object value : values) {
            String text = Objects.toString(value, "");
            if (text.stripLeading().matches("(?s)^[=+@\\-\\t\\r\\n].*")) text = "'" + text;
            line.add("\"" + text.replace("\"", "\"\"") + "\"");
        }
        out.append(line).append("\r\n");
    }
    @SuppressWarnings("unchecked")
    private static void draft(Model model, Map<String, String> defaults) {
        Object previous = model.getAttribute("draft");
        if (previous instanceof Map<?, ?> map) defaults.putAll((Map<String, String>) map);
        model.addAttribute("draft", defaults);
    }
    private static String editQuery(Long id) { return id == null ? "" : "?edit=" + id; }
    private static String attempt(Supplier<String> work, String failureUrl, Map<String, String> input,
                                  RedirectAttributes flash, String notice) {
        try { String target = work.get(); flash.addFlashAttribute("notice", notice); return "redirect:" + target; }
        catch (IllegalArgumentException ex) {
            Map<String, String> draft = new HashMap<>(input); draft.remove("_csrf"); draft.remove("confirmOverlap");
            flash.addFlashAttribute("draft", draft); flash.addFlashAttribute("error", ex.getMessage());
            if (ex instanceof SchedulerService.OverlapException) flash.addFlashAttribute("overlap", true);
            return "redirect:" + failureUrl;
        }
    }
    public record VolunteerReport(Volunteer volunteer, List<Assignment> assignments) { }
}
